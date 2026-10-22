// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// A list row with a Phosphor icon in front of its text.
struct IconLabel: View {
    let icon: PhosphorIcon
    let text: String
    var tint: Color = BipeColor.inkPrimary

    var body: some View {
        Label {
            Text(text)
                .foregroundStyle(tint)
        } icon: {
            icon.image
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(tint)
        }
    }
}

/// A short notice at the bottom of the screen that hides itself.
private struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkOnAccent)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(BipeColor.accentFill))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: message) {
                        try? await Task.sleep(for: .seconds(3))
                        withAnimation { self.message = nil }
                    }
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .animation(.default, value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}

extension UIImage {
    /// The centre square, drawn upright: the "square crop" of the profile photo specs.
    func centreSquare() -> UIImage {
        let side = min(size.width, size.height)
        guard side > 0, size.width != size.height else { return self }
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            draw(at: CGPoint(x: -(size.width - side) / 2, y: -(size.height - side) / 2))
        }
    }
}
