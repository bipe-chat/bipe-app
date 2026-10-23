// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreImage
import Foundation
import ImageIO

/// Type PixSharedItem of spec pix-check-receive. The image is a temporary local copy, never a remote URL.
enum PixSharedItem: Equatable {
    case text(String)
    case image(URL)
}

/// Type PixCheckedItem of spec pix-check-result: one checked text or QR payload.
struct PixCheckedItem: Identifiable, Equatable, Sendable {
    let id = UUID()
    /// The provider item index; an image can give several reports.
    let sourceIndex: Int
    /// The checked text or QR payload, kept on the phone for the send preview only.
    let input: String
    let check: PixCheck

    var code: PixCheck.Code? { check.code }
    var keys: [PixCheck.Key] { check.keys }
    /// The rule warning, never pixCode or pixKey (PixCheck skips them).
    var warningKind: ScamWarningKind? { check.warning }

    static func == (lhs: PixCheckedItem, rhs: PixCheckedItem) -> Bool { lhs.id == rhs.id }
}

/// Spec pix-check-receive: reads shared text or QR images and runs the Pix check on the phone.
/// No account, no network: nothing here starts a request.
@MainActor
final class PixCheckReceiveModel: ObservableObject {
    enum Phase: Equatable {
        case missingInput
        case reading
        case checking
        case ready
        case unsupported
        case noQr
        case nothingFound
        case failed
        case cancelled
    }

    /// Limits of the spec notes.
    static let maxItems = 10
    static let maxTextScalars = 100_000
    static let maxImageBytes = 10 * 1024 * 1024
    static let maxImagePixels = 4_000_000

    @Published private(set) var phase: Phase = .reading
    @Published private(set) var results: [PixCheckedItem] = []
    @Published private(set) var canRetry = false
    /// isHandingOff and handoffFailed of spec pix-check-result.
    @Published private(set) var isHandingOff = false
    @Published var handoffFailed = false

    private var items: [PixSharedItem] = []
    /// Every operation has a generation: a newer share or a cancel makes older completions inert.
    private var generation = 0
    private var work: Task<Void, Never>?

    /// Action receive.
    func receive(_ items: [PixSharedItem]) {
        generation += 1
        work?.cancel()
        deleteImages(except: items)
        self.items = items
        results = []
        canRetry = false
        guard !items.isEmpty else {
            phase = .missingInput
            return
        }
        run(generation: generation)
    }

    /// Action retry: repeats the local read and check from the kept input.
    func retry() {
        guard canRetry else { return }
        generation += 1
        canRetry = false
        run(generation: generation)
    }

    /// Action cancel: stops the work, deletes the temporary files. No later result shows.
    func cancel() {
        generation += 1
        work?.cancel()
        deleteImages(except: [])
        items = []
        results = []
        phase = .cancelled
    }

    /// Action close: deletes the kept input and results.
    func close() {
        cancel()
    }

    /// Action sendToChat (iOS extension): saves the pending preview of the original input, never sends.
    /// Returns true when the preview is saved; retryHandoff is a new tap.
    func handOff(_ item: PixCheckedItem) -> Bool {
        isHandingOff = true
        defer { isHandingOff = false }
        var image: URL?
        if items.indices.contains(item.sourceIndex), case .image(let url) = items[item.sourceIndex] {
            image = url
        }
        do {
            try PixHandoff.save(text: image == nil ? item.input : nil, image: image)
            handoffFailed = false
            return true
        } catch {
            handoffFailed = true
            return false
        }
    }

    private func run(generation: Int) {
        let items = items
        phase = items.contains { if case .image = $0 { true } else { false } } ? .reading : .checking
        work = Task { [weak self] in
            let outcome = await Task.detached(priority: .userInitiated) { Self.check(items) }.value
            guard let self, !Task.isCancelled, generation == self.generation else { return }
            results = outcome.results
            canRetry = outcome.retryable
            phase = outcome.phase
        }
    }

    private struct Outcome: Sendable {
        var results: [PixCheckedItem] = []
        var phase: Phase = .nothingFound
        var retryable = false
    }

    /// Same order on both platforms: provider order; per image, distinct QR payloads sorted lexically.
    /// Each payload is checked on its own, never joined into one BR Code.
    private nonisolated static func check(_ items: [PixSharedItem]) -> Outcome {
        var outcome = Outcome()
        var checkedInputs = 0
        var imagesWithoutQr = 0
        var rejected = 0
        var failed = false
        for (index, item) in items.prefix(maxItems).enumerated() {
            switch item {
            case .text(let text):
                guard text.unicodeScalars.count <= maxTextScalars else {
                    rejected += 1
                    continue
                }
                checkedInputs += 1
                outcome.results.append(PixCheckedItem(sourceIndex: index, input: text, check: PixCheck.check(text)))
            case .image(let url):
                switch qrPayloads(at: url) {
                case .tooLarge:
                    rejected += 1
                case .unreadable:
                    failed = true
                    outcome.retryable = FileManager.default.fileExists(atPath: url.path)
                case .payloads(let payloads):
                    if payloads.isEmpty { imagesWithoutQr += 1 }
                    for payload in payloads {
                        checkedInputs += 1
                        outcome.results.append(PixCheckedItem(sourceIndex: index, input: payload, check: PixCheck.check(payload)))
                    }
                }
            }
        }
        rejected += max(0, items.count - maxItems)
        if outcome.results.contains(where: { $0.check.foundPix }) {
            outcome.phase = .ready
        } else if failed && checkedInputs == 0 {
            outcome.phase = .failed
        } else if checkedInputs > 0 {
            // A rule warning stays in the results even when no Pix was found.
            outcome.phase = .nothingFound
        } else if imagesWithoutQr > 0 {
            outcome.phase = .noQr
        } else if rejected > 0 {
            outcome.phase = .unsupported
        } else {
            outcome.phase = .missingInput
        }
        return outcome
    }

    private enum ImageRead {
        case payloads([String])
        case tooLarge
        case unreadable
    }

    /// Decodes on the phone. The image is checked for size first and shrunk to at most 4 megapixels.
    private nonisolated static func qrPayloads(at url: URL) -> ImageRead {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else { return .unreadable }
        guard size <= maxImageBytes else { return .tooLarge }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return .unreadable }
        let pixels = Double(width * height)
        let scale = pixels > Double(maxImagePixels) ? (Double(maxImagePixels) / pixels).squareRoot() : 1
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(Double(max(width, height)) * scale),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return .unreadable }
        let payloads = Set(PixCheck.qrPayloads(in: CIImage(cgImage: image)))
        return .payloads(payloads.sorted())
    }

    private func deleteImages(except kept: [PixSharedItem]) {
        for case .image(let url) in items where !kept.contains(.image(url)) {
            try? FileManager.default.removeItem(at: url)
        }
    }
}
