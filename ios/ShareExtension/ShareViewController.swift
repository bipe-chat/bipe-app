// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// The share extension: send to a chat (spec share-to-chat) and Conferir no Bipe (specs pix-check-receive and
/// pix-check-result). The Pix check and its result stay inside the extension, so a cold or signed-out app cannot
/// block it. Send to a chat only copies the items into the app group; the app sends after chat choice and Send.
final class ShareViewController: UIViewController {
    private var model: ShareRootModel?

    override func viewDidLoad() {
        super.viewDidLoad()
        RoundedType.applyToBars()
        // Spec app-colors: the same palette as the app.
        AppTheme.load()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let model = ShareRootModel(providers: providers)
        self.model = model
        let host = UIHostingController(rootView: ShareRootView(model: model,
                                                               close: { [weak self] in self?.finish() },
                                                               pixSendToChat: { [weak self] item in
            // Only after the tap: the preview waits in the app group; finishing does not mean it was sent.
            if model.pix.handOff(item) {
                model.pix.close()
                self?.finish()
            }
        }).bipeRoundedType())
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    /// Close: the models already deleted their files; the extension request completes.
    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}
