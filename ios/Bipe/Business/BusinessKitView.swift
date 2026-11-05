// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreImage.CIFilterBuiltins
import Phosphor
import SwiftUI
import UIKit

/// Spec business-kit: a button for the company's site, a QR code and ready lines, for its own channels (ADR 0082).
struct BusinessKitView: View {
    let people: PeopleService

    @State private var handle: String?
    @State private var loaded = false
    @State private var shared: SharedKitFile?
    @State private var notice: String?

    private var link: URL? {
        handle.flatMap(BipeProfileLink.url(handle:))
    }

    var body: some View {
        List {
            if let link {
                content(link: link)
            } else if loaded {
                Text(BipeStrings.publicProfileNeedsHandle)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier(BipeScreen.businessKit.rawValue)
        .navigationTitle(BipeStrings.businessKitTitle)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $shared) { file in
            ShareSheet(items: [file.url])
        }
        .task {
            let mine = try? await people.withOpenID { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeMyHandle in
                try await people.api.myHandle(openID: token)
            }
            handle = mine?.handle
            loaded = true
        }
    }

    @ViewBuilder
    private func content(link: URL) -> some View {
        let address = link.absoluteString
        Section {
            Text(BipeStrings.businessKitIntro)
                .foregroundStyle(BipeColor.inkPrimary)
        } footer: {
            if let notice { Text(notice) }
        }
        Section {
            let code = BipeStrings.businessKitButtonCode(address, BipeStrings.businessKitButtonLabel)
            Text(code)
                .font(.footnote.monospaced())
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
            Button {
                copy(code)
            } label: {
                IconLabel(icon: .copy, text: BipeStrings.businessKitCopyCode)
            }
        } header: {
            Text(BipeStrings.businessKitButtonHeader)
        } footer: {
            Text(BipeStrings.businessKitButtonFooter)
        }
        Section {
            QRCodeImage(text: address)
                .frame(width: 240, height: 240)
                .frame(maxWidth: .infinity)
            Button {
                share(BusinessKitQr.png(address), name: "bipe-qr.png")
            } label: {
                IconLabel(icon: .shareNetwork, text: BipeStrings.businessKitSharePng)
            }
            Button {
                share(BusinessKitQr.svg(address), name: "bipe-qr.svg")
            } label: {
                IconLabel(icon: .shareNetwork, text: BipeStrings.businessKitShareSvg)
            }
        } header: {
            Text(BipeStrings.businessKitQrHeader)
        } footer: {
            Text(BipeStrings.businessKitQrFooter)
        }
        Section {
            ForEach([BipeStrings.businessKitLineSite(address),
                     BipeStrings.businessKitLineEmail(address),
                     BipeStrings.businessKitLineReceipt(address)], id: \.self) { line in
                Button {
                    copy(line)
                } label: {
                    Text(line).foregroundStyle(BipeColor.inkPrimary)
                }
                .accessibilityHint(BipeStrings.appCopy)
            }
        } header: {
            Text(BipeStrings.businessKitLinesHeader)
        } footer: {
            Text(BipeStrings.businessKitRule)
        }
    }

    private func copy(_ text: String) {
        UIPasteboard.general.string = text
        notice = BipeStrings.appCopied
    }

    private func share(_ data: Data?, name: String) {
        guard let data else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return }
        shared = SharedKitFile(url: url)
    }
}

private struct SharedKitFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// The QR code as files: dark modules on white with a quiet zone of 4 modules, for a printer or a scanner in any theme.
enum BusinessKitQr {
    private static let quietZone = 4
    private static let pngScale = 16

    /// The modules of the code, row by row, without the margin the filter adds.
    static func modules(_ text: String) -> [[Bool]]? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        let width = image.width
        let height = image.height
        var bytes = [UInt8](repeating: 255, count: width * height)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                                          bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        let rows = (0..<height).map { y in (0..<width).map { x in bytes[y * width + x] < 128 } }
        let kept = rows.filter { $0.contains(true) }
        guard let first = kept.first else { return nil }
        let columns = (0..<first.count).filter { x in kept.contains { $0[x] } }
        guard let left = columns.first, let right = columns.last else { return nil }
        return kept.map { Array($0[left...right]) }
    }

    static func svg(_ text: String) -> Data? {
        guard let modules = modules(text) else { return nil }
        let size = modules.count + 2 * quietZone
        var path = ""
        for (y, row) in modules.enumerated() {
            for (x, dark) in row.enumerated() where dark {
                path += "M\(x + quietZone) \(y + quietZone)h1v1h-1z"
            }
        }
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 \(size) \(size)\" shape-rendering=\"crispEdges\">"
            + "<rect width=\"\(size)\" height=\"\(size)\" fill=\"#fff\"/><path d=\"\(path)\" fill=\"#000\"/></svg>"
        return Data(svg.utf8)
    }

    static func png(_ text: String) -> Data? {
        guard let modules = modules(text) else { return nil }
        let side = (modules.count + 2 * quietZone) * pngScale
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).pngData { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
            UIColor.black.setFill()
            for (y, row) in modules.enumerated() {
                for (x, dark) in row.enumerated() where dark {
                    context.fill(CGRect(x: (x + quietZone) * pngScale, y: (y + quietZone) * pngScale, width: pngScale, height: pngScale))
                }
            }
        }
    }
}
