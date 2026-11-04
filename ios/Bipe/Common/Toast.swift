// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Bipe's one toast (bipe/docs/design/toasts.md): a short result at the bottom of the screen for 3 seconds.
/// A new message replaces the one on screen. VoiceOver reads it out.
struct ToastModifier: ViewModifier {
    static let duration: Duration = .seconds(3)

    @Binding var message: String?
    /// Space below the toast: more on a screen with a composer or a bottom bar.
    var bottom: CGFloat = 24

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(BipeColor.surfaceRaised).shadow(radius: 4))
                    .padding(.horizontal, 24)
                    .padding(.bottom, bottom)
                    .transition(.bipeMove(edge: .bottom))
                    .task(id: message) {
                        AccessibilityNotification.Announcement(message).post()
                        try? await Task.sleep(for: Self.duration)
                        withBipeAnimation { self.message = nil }
                    }
            }
        }
        .bipeAnimation(.default, value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>, bottom: CGFloat = 24) -> some View {
        modifier(ToastModifier(message: message, bottom: bottom))
    }
}
