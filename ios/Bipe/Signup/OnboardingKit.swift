// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

// The look of the sign-up flow (bipe-chat/bipe#1308): one set of pieces for every step, so that each screen
// carries the Bipe brand of onboarding-welcome. The Android twin is signup/OnboardingKit.kt. Colours are tokens.

enum OnboardingMetrics {
    /// The height of a filled or tonal button. Larger than the 44 pt minimum: these are the screen's main choices.
    static let buttonHeight: CGFloat = 56
    static let buttonCorner: CGFloat = 16
    static let fieldCorner: CGFloat = 16
    static let tileCorner: CGFloat = 18
    /// The steps that the progress bar counts: name, PIN, fallback (spec flow signup).
    static let progressSteps = 3
}

// MARK: - Buttons

/// The one filled button of a step: açaí, tall, with a soft açaí shadow and a press state.
struct OnboardingFilledButtonStyle: ButtonStyle {
    var busy = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let active = isEnabled || busy
        ZStack {
            configuration.label.opacity(busy ? 0 : 1)
            if busy { ProgressView().tint(BipeColor.inkOnAccent) }
        }
        .font(.headline)
        .frame(maxWidth: .infinity, minHeight: OnboardingMetrics.buttonHeight)
        .padding(.horizontal, 20)
        .foregroundStyle(active ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
        .background {
            RoundedRectangle(cornerRadius: OnboardingMetrics.buttonCorner, style: .continuous)
                .fill(active ? (configuration.isPressed ? BipeColor.accentFillPressed : BipeColor.accentFill)
                             : BipeColor.surfaceSunken)
                .shadow(color: active ? BipeColor.accentFill.opacity(configuration.isPressed ? 0.15 : 0.3) : .clear,
                        radius: configuration.isPressed ? 4 : 12, y: configuration.isPressed ? 2 : 6)
        }
        .contentShape(RoundedRectangle(cornerRadius: OnboardingMetrics.buttonCorner, style: .continuous))
        .scaleEffect(configuration.isPressed ? 0.98 : 1)
        .bipeAnimation(.spring(duration: 0.25), value: configuration.isPressed)
    }
}

/// The second choice next to a filled button: same size, açaí wash with an açaí edge.
struct OnboardingTonalButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: OnboardingMetrics.buttonHeight)
            .padding(.horizontal, 16)
            .foregroundStyle(isEnabled ? BipeColor.accentInk : BipeColor.inkDisabled)
            .background {
                let shape = RoundedRectangle(cornerRadius: OnboardingMetrics.buttonCorner, style: .continuous)
                shape.fill(BipeColor.accentWash)
                    .overlay { shape.fill(BipeColor.accentFill.opacity(configuration.isPressed ? 0.12 : 0)) }
                    .overlay { shape.strokeBorder(BipeColor.accentFill.opacity(isEnabled ? 0.28 : 0.1), lineWidth: 1.5) }
            }
            .contentShape(RoundedRectangle(cornerRadius: OnboardingMetrics.buttonCorner, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .bipeAnimation(.spring(duration: 0.25), value: configuration.isPressed)
    }
}

/// A quiet choice under the filled button: açaí text in a full-width row that dims on press.
struct OnboardingTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(isEnabled ? BipeColor.accentInk : BipeColor.inkDisabled)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.55 : 1)
    }
}

struct OnboardingPrimaryButton: View {
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text(title) }
            .buttonStyle(OnboardingFilledButtonStyle(busy: busy))
            .disabled(!enabled || busy)
    }
}

struct OnboardingSecondaryButton: View {
    let title: String
    var icon: PhosphorIcon?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { icon.image.resizable().frame(width: 20, height: 20) }
                Text(title)
            }
        }
        .buttonStyle(OnboardingTonalButtonStyle())
    }
}

struct OnboardingTextButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text(title).multilineTextAlignment(.center) }
            .buttonStyle(OnboardingTextButtonStyle())
    }
}

// MARK: - Step frame

/// The backdrop of every step: an açaí wash that fades into the page, with one large faint Bipe mark at the top
/// trailing edge, the motif of the welcome cover.
struct OnboardingBackdrop: View {
    var body: some View {
        GeometryReader { page in
            ZStack(alignment: .topTrailing) {
                BipeColor.surfaceBase
                LinearGradient(colors: [BipeColor.accentWash, BipeColor.surfaceBase],
                               startPoint: .top, endPoint: .bottom)
                    .frame(height: min(320, page.size.height * 0.45))
                    .frame(maxHeight: .infinity, alignment: .top)
                // Upright only: the notch stays top-leading, as on the icon.
                BipeMark()
                    .fill(BipeColor.accentFill.opacity(0.07))
                    .frame(width: 220, height: 220)
                    .offset(x: 70, y: -40)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// The header of a step: a rounded tile with the step's glyph and, on the three typing steps, a progress bar.
struct OnboardingStepHeader: View {
    let icon: PhosphorIcon
    /// 1-based; nil hides the bar (invite code, waitlist).
    var step: Int?

    var body: some View {
        HStack(alignment: .center) {
            // Never the seal shape for a tile: only the seal means "verified" (identity.md, Icons).
            icon.image
                .resizable()
                .frame(width: 28, height: 28)
                .foregroundStyle(BipeColor.inkOnAccent)
                .frame(width: 56, height: 56)
                .background {
                    RoundedRectangle(cornerRadius: OnboardingMetrics.tileCorner, style: .continuous)
                        .fill(LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .shadow(color: BipeColor.accentFill.opacity(0.25), radius: 10, y: 4)
                }
            Spacer(minLength: 16)
            if let step {
                OnboardingProgress(step: step)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Three short bars: the done and current steps in açaí, the rest sunken. Decorative: the title names the step.
struct OnboardingProgress: View {
    let step: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(1...OnboardingMetrics.progressSteps, id: \.self) { index in
                Capsule()
                    .fill(index <= step ? BipeColor.accentFill : BipeColor.lineSubtle)
                    .frame(width: index == step ? 28 : 14, height: 6)
            }
        }
    }
}

/// A sign-up step: backdrop, header, title, optional body, content in a scroll view, and an action bar pinned
/// above the keyboard and the home indicator.
struct OnboardingStepLayout<Content: View, Actions: View>: View {
    let screen: BipeScreen
    let icon: PhosphorIcon
    var step: Int?
    let title: String
    var message: String?
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                OnboardingStepHeader(icon: icon, step: step)
                    .padding(.bottom, 8)
                VStack(alignment: .leading, spacing: 10) {
                    Text(title)
                        .font(.largeTitle.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    if let message {
                        Text(message)
                            .font(.body)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                content
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 4) { actions }
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 12)
                .background {
                    // The bar fades over the content that scrolls under it.
                    LinearGradient(colors: [BipeColor.surfaceBase.opacity(0), BipeColor.surfaceBase],
                                   startPoint: .top, endPoint: .init(x: 0.5, y: 0.3))
                        .ignoresSafeArea(edges: .bottom)
                }
        }
        .background(OnboardingBackdrop())
        .accessibilityIdentifier(screen.rawValue)
    }
}

extension OnboardingStepLayout where Actions == EmptyView {
    init(screen: BipeScreen, icon: PhosphorIcon, step: Int? = nil, title: String, message: String? = nil,
         @ViewBuilder content: () -> Content) {
        self.init(screen: screen, icon: icon, step: step, title: title, message: message,
                  content: content, actions: { EmptyView() })
    }
}

// MARK: - Fields

struct OnboardingFieldFooter: View {
    let text: String
    var isError = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            // The icon says "error" next to the colour (bipe#497: never colour alone).
            if isError {
                PhosphorIcon.warningCircle.image
                    .resizable()
                    .frame(width: 14, height: 14)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 2 }
            }
            Text(text)
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(isError ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
    }
}

/// A framed field: raised surface, a thin line, an açaí ring while it has focus.
struct OnboardingTextField: View {
    let label: String
    let text: String
    let onChange: (String) -> Void
    var icon: PhosphorIcon?
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var isCode = false
    var isError = false
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                icon.image
                    .resizable()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(focused ? BipeColor.accentInk : BipeColor.glyphSecondary)
            }
            TextField(label, text: Binding(get: { text }, set: onChange))
                .font(isCode ? .title3.monospaced().weight(.semibold) : .body)
                .keyboardType(keyboard)
                .textContentType(contentType)
                .textInputAutocapitalization(isCode ? .characters : keyboard == .emailAddress ? .never : .words)
                .autocorrectionDisabled(isCode || keyboard == .emailAddress)
                .focused($focused)
                .foregroundStyle(BipeColor.inkPrimary)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: OnboardingMetrics.buttonHeight)
        .background {
            let shape = RoundedRectangle(cornerRadius: OnboardingMetrics.fieldCorner, style: .continuous)
            shape.fill(BipeColor.surfaceRaised)
                .overlay {
                    shape.strokeBorder(isError ? BipeColor.trustDangerLine
                                       : focused ? BipeColor.accentFill : BipeColor.lineSubtle,
                                       lineWidth: focused || isError ? 2 : 1)
                }
        }
        .contentShape(Rectangle())
        // a11y: the text field inside is the element; the tap only widens its hit area.
        .onTapGesture { focused = true }
        .bipeAnimation(.easeOut(duration: 0.15), value: focused)
    }
}

/// The clickwrap box: a rounded square with a check when on.
struct OnboardingCheckbox: View {
    let checked: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 7, style: .continuous)
        ZStack {
            shape.fill(checked ? BipeColor.accentFill : BipeColor.surfaceRaised)
            shape.strokeBorder(checked ? BipeColor.accentFill : BipeColor.lineStrong, lineWidth: 2)
            if checked {
                PhosphorIcon.check.image
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.inkOnAccent)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .frame(width: 26, height: 26)
        .bipeAnimation(.spring(duration: 0.25), value: checked)
    }
}

/// A card for a group of content on a step: raised, rounded, with a thin line.
struct OnboardingCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
                shape.fill(BipeColor.surfaceRaised).overlay { shape.strokeBorder(BipeColor.lineSubtle) }
            }
    }
}

// MARK: - Progress

/// The branded wait state: the Bipe mark that breathes inside a soft açaí ring. Still under Reduce Motion.
struct OnboardingBusyMark: View {
    @State private var breathing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle()
                .fill(BipeColor.accentWash)
                .frame(width: 128, height: 128)
                .scaleEffect(breathing ? 1.08 : 0.92)
            Circle()
                .strokeBorder(BipeColor.accentFill.opacity(0.25), lineWidth: 2)
                .frame(width: 128, height: 128)
                .scaleEffect(breathing ? 1.25 : 1)
                .opacity(breathing ? 0 : 1)
            BipeMark()
                .fill(LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 64, height: 64)
                .scaleEffect(breathing ? 1.04 : 0.96)
        }
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withBipeAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { breathing = true }
        }
    }
}
