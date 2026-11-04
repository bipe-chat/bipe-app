// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Action shareResult of spec pix-check-result (bipe#746, phase 1): the text and card that go back to the chat.
/// The text states only what the code says, never that the Pix is safe. It is a check result, never a payment action.
enum PixShareResult {
    /// The text for one report, or nil when the report has nothing to send back.
    static func text(for item: PixCheckedItem) -> String? {
        if let code = item.code {
            switch code.status {
            case .badCrc, .malformed:
                return BipeStrings.pixCheckShareAltered
            case .valid:
                if let name = code.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
                    if let amount = code.amount {
                        return BipeStrings.pixCheckShareCode(name, PixFormat.amount(amount))
                    }
                    return BipeStrings.pixCheckShareCodeOpenAmount(name)
                }
            }
        }
        if let key = item.keys.first {
            return BipeStrings.pixCheckShareKey(key.value)
        }
        return nil
    }

    /// The card image: the same text as plain text, no link or QR, on the light theme.
    @MainActor static func card(text: String) -> UIImage? {
        let renderer = ImageRenderer(content: PixShareCard(text: text)
            .frame(width: 360)
            .environment(\.colorScheme, .light)
            .bipeRoundedType())
        renderer.scale = 3
        return renderer.uiImage
    }
}

/// The share sheet items of one report: the text, then the card when it renders.
struct PixSharePayload: Identifiable {
    let id = UUID()
    let items: [Any]

    @MainActor init?(item: PixCheckedItem) {
        guard let text = PixShareResult.text(for: item) else { return nil }
        if let image = PixShareResult.card(text: text) {
            items = [image, text]
        } else {
            items = [text]
        }
    }
}

private struct PixShareCard: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                PhosphorIcon.magnifyingGlass.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.pixCheckTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.accentInk)
            }
            Text(text)
                .font(.title3.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BipeColor.surfaceRaised)
    }
}

/// The system share sheet. The person picks the app and the chat; nothing leaves the phone before that.
struct PixShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
