// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Conferir no Bipe (specs pix-check-receive and pix-check-result): the check and its result stay inside the
/// extension, so a cold or signed-out app cannot block it. No account, no network.
final class ShareViewController: UIViewController {
    private let model = PixCheckReceiveModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let model = model
        RoundedType.applyToBars()
        let host = UIHostingController(rootView: PixCheckView(model: model, accountAvailable: PixHandoff.accountAvailable,
                                                              close: { [weak self] in self?.finish() },
                                                              sendToChat: { [weak self] item in
            // Only after the tap: the preview waits in the app group; finishing does not mean it was sent.
            if model.handOff(item) {
                model.close()
                self?.finish()
            }
        }).bipeRoundedType())
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        Task { [weak self] in
            guard let self else { return }
            let items = await Self.items(from: extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            model.receive(items)
        }
    }

    /// Action close: the model already deleted its files; the extension request completes.
    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// The supported items in provider order: plain text, and images copied to a temporary local file
    /// while the provider's read grant is valid. A remote URL is never fetched.
    private static func items(from extensionItems: [NSExtensionItem]) async -> [PixSharedItem] {
        var items: [PixSharedItem] = []
        let providers = extensionItems.flatMap { $0.attachments ?? [] }
        for provider in providers.prefix(PixCheckReceiveModel.maxItems + 1) {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                if let url = await copyImage(provider) {
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

    private static func loadText(_ provider: NSItemProvider) async -> String? {
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

    /// Copies the provider's file before its temporary URL goes away. Over 10 MiB is not copied.
    private static func copyImage(_ provider: NSItemProvider) async -> URL? {
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
