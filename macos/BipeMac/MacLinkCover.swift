// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The açaí cover of the link screen (bipe-chat/bipe#1308): the same cover as the phones' onboarding-welcome, so the
/// first thing on the computer looks like the first thing on the phone. Mark, wordmark, the welcome line and a faint
/// texture of Bipe marks. Colours are tokens.
struct MacLinkCover: View {
    @ScaledMetric(relativeTo: .largeTitle) private var wordmarkSize: CGFloat = 52

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                BipeMark()
                    .frame(width: 44, height: 44)
                    .shadow(color: BipeColor.accentFillPressed.opacity(0.4), radius: 8, y: 3)
                    .accessibilityHidden(true)
                Text(BipeStrings.appName.lowercased())
                    .font(.system(size: wordmarkSize, weight: .heavy, design: .rounded))
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 24)
            Text(BipeStrings.onboardingTitle)
                .font(.system(.largeTitle, design: .rounded, weight: .bold))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(BipeColor.inkOnAccent)
        .padding(36)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            ZStack {
                LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                MacMarkTexture().accessibilityHidden(true)
            }
        }
        .clipped()
    }
}

/// Bipe marks at low contrast over the cover, at the same places as the phone cover (SignupViews.swift BubbleTexture).
private struct MacMarkTexture: View {
    private struct Mark {
        let x: CGFloat, y: CGFloat, size: CGFloat, opacity: Double
    }

    private let marks = [
        Mark(x: 0.86, y: 0.22, size: 0.50, opacity: 0.12),
        Mark(x: 0.42, y: 0.36, size: 0.20, opacity: 0.07),
        Mark(x: 1.00, y: 0.66, size: 0.30, opacity: 0.09),
        Mark(x: 0.62, y: 0.44, size: 0.10, opacity: 0.12),
    ]

    var body: some View {
        GeometryReader { panel in
            let side = min(panel.size.width, panel.size.height * 1.4)
            ForEach(marks.indices, id: \.self) { index in
                let mark = marks[index]
                // Upright only: the notch stays top-leading, as on the icon.
                BipeMark()
                    .fill(BipeColor.inkOnAccent.opacity(mark.opacity))
                    .frame(width: side * mark.size, height: side * mark.size)
                    .position(x: panel.size.width * mark.x, y: panel.size.height * mark.y)
            }
        }
    }
}

/// The filled button of the link screen: açaí, tall, rounded, with a soft açaí shadow. The phones' filled button
/// (OnboardingKit.swift) in the Mac's sizes.
struct MacFilledButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        configuration.label
            .macFont(.headline)
            .padding(.horizontal, 28)
            .frame(minWidth: 200, minHeight: 48)
            .foregroundStyle(isEnabled ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
            .background {
                shape.fill(isEnabled ? (configuration.isPressed ? BipeColor.accentFillPressed : BipeColor.accentFill)
                                     : BipeColor.surfaceSunken)
                    .shadow(color: isEnabled ? BipeColor.accentFill.opacity(0.3) : .clear, radius: 10, y: 4)
            }
            .contentShape(shape)
    }
}
