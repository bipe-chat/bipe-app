// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UniformTypeIdentifiers

/// The two actions of the share extension: send to a chat (spec share-to-chat) and Conferir no Bipe
/// (specs pix-check-receive and pix-check-result).
@MainActor
final class ShareRootModel: ObservableObject {
    enum Phase: Equatable {
        case choose
        case pix
        case copying
        case handedOff(droppedOverLimit: Bool)
        case signedOut
        case nothingRead
        case failed
    }

    @Published private(set) var phase: Phase = .choose
    let pix = PixCheckReceiveModel()
    let providers: [NSItemProvider]
    private var writer: ShareHandoff.Writer?
    private var work: Task<Void, Never>?

    init(providers: [NSItemProvider]) {
        self.providers = providers
    }

    /// Conferir no Bipe reads only text and images.
    var canCheckPix: Bool {
        providers.contains {
            $0.hasItemConformingToTypeIdentifier(UTType.image.identifier)
                || $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
        }
    }

    func checkPix() {
        phase = .pix
        let providers = providers
        Task { [pix] in
            pix.receive(await ShareItemReader.pixItems(from: providers))
        }
    }

    /// Action receive of spec share-to-chat: copies the items into the app group. With no account on the phone,
    /// nothing is copied.
    func sendToChat() {
        guard PixHandoff.accountAvailable else {
            phase = .signedOut
            return
        }
        phase = .copying
        let providers = providers
        work = Task { [weak self] in
            guard let writer = try? ShareHandoff.Writer() else {
                self?.phase = .failed
                return
            }
            self?.writer = writer
            let dropped = await ShareItemReader.copy(providers, into: writer)
            guard let self, !Task.isCancelled else { return }
            if writer.isEmpty {
                writer.discard()
                phase = .nothingRead
                return
            }
            do {
                try writer.commit(droppedOverLimit: dropped)
                self.writer = nil
                phase = .handedOff(droppedOverLimit: dropped)
            } catch {
                phase = .failed
            }
        }
    }

    /// Action cancel: stops the copy and deletes every copy and Pix input.
    func cancel() {
        work?.cancel()
        writer?.discard()
        writer = nil
        pix.close()
    }
}

/// Reads NSItemProvider items. A remote URL is never fetched: a web link goes as text.
enum ShareItemReader {
    /// The items of Conferir no Bipe, as before: plain text, and images copied while the read grant is valid.
    static func pixItems(from providers: [NSItemProvider]) async -> [PixSharedItem] {
        var items: [PixSharedItem] = []
        for provider in providers.prefix(PixCheckReceiveModel.maxItems + 1) {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                if let url = await copyPixImage(provider) {
                    items.append(.image(url))
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let text = await loadText(provider) {
                    items.append(.text(text))
                }
            }
        }
        return items
    }

    /// Copies the first 10 items in provider order into the pending share. Returns true when the share had more.
    static func copy(_ providers: [NSItemProvider], into writer: ShareHandoff.Writer) async -> Bool {
        for (index, provider) in providers.prefix(ShareHandoff.maxItems).enumerated() {
            if Task.isCancelled { return false }
            if let type = fileType(of: provider) {
                if let copied = await copyFile(provider, type: type, index: index, writer: writer) {
                    writer.addFile(path: copied.path, mimeType: copied.mimeType)
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                if let url = await loadURL(provider) {
                    if url.isFileURL {
                        if let copied = copyLocalFile(url, index: index, writer: writer) {
                            writer.addFile(path: copied.path, mimeType: copied.mimeType)
                        }
                    } else {
                        writer.addText(url.absoluteString)
                    }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let text = await loadText(provider), !text.isEmpty {
                    writer.addText(text)
                }
            }
        }
        return providers.count > ShareHandoff.maxItems
    }

    /// Photos and videos first, then any other file data. Links and text are not files.
    private static func fileType(of provider: NSItemProvider) -> String? {
        for type in [UTType.movie, UTType.image] where provider.hasItemConformingToTypeIdentifier(type.identifier) {
            return type.identifier
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier)
            || provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            return nil
        }
        return provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .data) == true }
    }

    private static func copyFile(_ provider: NSItemProvider, type: String, index: Int,
                                 writer: ShareHandoff.Writer) async -> (path: String, mimeType: String?)? {
        let suggested = provider.suggestedName
        return await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                // The temporary URL is valid only inside this block.
                guard let url else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: copyLocalFile(url, suggestedName: suggested, index: index, writer: writer))
            }
        }
    }

    private static func copyLocalFile(_ url: URL, suggestedName: String? = nil, index: Int,
                                      writer: ShareHandoff.Writer) -> (path: String, mimeType: String?)? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var name = url.lastPathComponent
        if let suggestedName, !suggestedName.isEmpty {
            name = (suggestedName as NSString).pathExtension.isEmpty && !url.pathExtension.isEmpty
                ? suggestedName + "." + url.pathExtension : suggestedName
        }
        guard let target = try? writer.destination(index: index, name: name),
              (try? FileManager.default.copyItem(at: url, to: target.url)) != nil else { return nil }
        let mimeType = UTType(filenameExtension: target.url.pathExtension)?.preferredMIMEType
        return (target.path, mimeType)
    }

    private static func loadURL(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                switch item {
                case let url as URL: continuation.resume(returning: url)
                case let data as Data: continuation.resume(returning: URL(dataRepresentation: data, relativeTo: nil))
                case let text as String: continuation.resume(returning: URL(string: text))
                default: continuation.resume(returning: nil)
                }
            }
        }
    }

    static func loadText(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                switch item {
                case let text as String: continuation.resume(returning: text)
                case let data as Data: continuation.resume(returning: String(data: data, encoding: .utf8))
                default: continuation.resume(returning: nil)
                }
            }
        }
    }

    /// Copies the provider's image before its temporary URL goes away. Over 10 MiB is not copied.
    private static func copyPixImage(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                guard let url,
                      let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                      size <= PixCheckReceiveModel.maxImageBytes else {
                    continuation.resume(returning: nil)
                    return
                }
                let copy = FileManager.default.temporaryDirectory
                    .appendingPathComponent("pix-\(UUID().uuidString)")
                    .appendingPathExtension(url.pathExtension)
                do {
                    try FileManager.default.copyItem(at: url, to: copy)
                    continuation.resume(returning: copy)
                } catch {
                    continuation.resume(returning: nil)
                }
            }
        }
    }
}

struct ShareRootView: View {
    @ObservedObject var model: ShareRootModel
    let close: () -> Void
    /// Action sendToChat of pix-check-result.
    let pixSendToChat: (PixCheckedItem) -> Void

    var body: some View {
        if model.phase == .pix {
            PixCheckView(model: model.pix, accountAvailable: PixHandoff.accountAvailable, close: close,
                         sendToChat: pixSendToChat)
        } else {
            NavigationStack {
                content
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            if model.phase == .choose || model.phase == .copying {
                                Button(BipeStrings.appCancel) {
                                    model.cancel()
                                    close()
                                }
                            }
                        }
                    }
            }
            .accessibilityIdentifier(BipeScreen.shareToChat.rawValue)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .choose, .pix:
            VStack(spacing: 12) {
                Button {
                    model.sendToChat()
                } label: {
                    Text(BipeStrings.shareSendToChat).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeProminent)
                if model.canCheckPix {
                    Button {
                        model.checkPix()
                    } label: {
                        Text(BipeStrings.pixCheckTitle).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeBordered)
                }
            }
        case .copying:
            ProgressView()
        case .handedOff(let dropped):
            message(dropped ? BipeStrings.shareTooMany + "\n\n" + BipeStrings.shareHandedOff : BipeStrings.shareHandedOff)
        case .signedOut:
            message(BipeStrings.shareSignedOut)
        case .nothingRead:
            message(BipeStrings.shareNothing)
        case .failed:
            message(BipeStrings.errorGeneric)
        }
    }

    private func message(_ text: String) -> some View {
        VStack(spacing: 16) {
            Text(text)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button {
                close()
            } label: {
                Text(BipeStrings.appClose).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
        }
    }
}
