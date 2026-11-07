// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// Spec account-start: the launch screen of iOS and macOS. The icon background of the stored palette, full bleed, one
/// turning ring, no text. The label is for screen readers only.
public struct BipeLaunchView: View {
    private let label: String
    private let fadesFromStandard: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showsStored: Bool

    public init(label: String, fadesFromStandard: Bool = false) {
        self.label = label
        self.fadesFromStandard = fadesFromStandard
        _showsStored = State(initialValue: !fadesFromStandard)
    }

    public var body: some View {
        ZStack {
            Self.fill(BipePalette.standard)
            Self.fill(BipeTheme.shared.palette).opacity(showsStored ? 1 : 0)
            TimelineView(.animation(paused: reduceMotion)) { context in
                Circle()
                    .trim(from: 0, to: BipeLaunchRing.arc)
                    .stroke(Self.color(BipePalette.iconMark), style: StrokeStyle(lineWidth: BipeLaunchRing.stroke, lineCap: .round))
                    .padding(BipeLaunchRing.stroke / 2)
                    .frame(width: BipeLaunchRing.diameter, height: BipeLaunchRing.diameter)
                    .rotationEffect(.degrees(Self.angle(at: context.date)))
            }
        }
        .ignoresSafeArea()
        .accessibilityElement()
        .accessibilityLabel(label)
        .onAppear {
            guard fadesFromStandard else { return }
            withAnimation(.easeOut(duration: 0.25)) { showsStored = true }
        }
    }

    private static func fill(_ palette: BipePalette) -> Color { color(palette.iconBackground) }

    private static func color(_ rgb: UInt32) -> Color {
        Color(.sRGB,
              red: Double((rgb >> 16) & 0xFF) / 255,
              green: Double((rgb >> 8) & 0xFF) / 255,
              blue: Double(rgb & 0xFF) / 255)
    }

    private static func angle(at date: Date) -> Double {
        date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: BipeLaunchRing.turnSeconds) / BipeLaunchRing.turnSeconds * 360
    }
}
