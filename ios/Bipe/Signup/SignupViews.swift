// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// The sign-up flow from onboarding-welcome to signup-create. account-start shows it when its phase is noAccount.
struct SignupFlowView: View {
    @StateObject private var model: SignupFlowModel
    /// The argument is true once signup-create ran (spec account-new cancelSignup).
    private let onCancel: ((_ reachedCreate: Bool) -> Void)?
    private let onRecoveryLink: (URL) -> Void

    init(api: BipeAPIClient,
         start: SignupStart? = nil,
         onCancel: ((_ reachedCreate: Bool) -> Void)? = nil,
         onSignedUp: @escaping (AccountSession, SignupOutcome) -> Void,
         onHaveAccount: @escaping () -> Void = {},
         onRecoveryLink: @escaping (URL) -> Void = { _ in }) {
        _model = StateObject(wrappedValue: SignupFlowModel(api: api, start: start, onSignedUp: onSignedUp,
                                                           onHaveAccount: onHaveAccount))
        self.onCancel = onCancel
        self.onRecoveryLink = onRecoveryLink
    }

    var body: some View {
        NavigationStack(path: $model.path) {
            root
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .toolbar {
                    if let onCancel {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(BipeStrings.appCancel) { onCancel(model.reachedCreate) }
                        }
                    }
                }
                .navigationDestination(for: SignupFlowModel.Route.self) { route in
                    Group {
                        switch route {
                        case .inviteCode: SignupInviteCodeView(model: model)
                        case .name: SignupNameView(model: model)
                        case .pin, .pinConfirm: SignupPinView(model: model, confirm: route == .pinConfirm)
                        case .fallback: SignupFallbackView(model: model)
                        case .create: SignupCreateView(model: model).navigationBarBackButtonHidden()
                        }
                    }
                    .signupColumn()
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                }
        }
        .tint(BipeColor.accentFill)
        .interactiveDismissDisabled(model.path.contains(.create))
        .sheet(isPresented: $model.showsWaitlist) {
            SignupWaitlistView(model: model)
        }
        .onOpenURL { url in
            // A recovery link goes to account-start (recovery-email, action openLink).
            if BipeRecoveryLink.token(from: url) != nil {
                onRecoveryLink(url)
            } else {
                Task { await model.openInviteLink(url) }
            }
        }
    }
}

extension SignupFlowView {
    /// onboarding-welcome; a sign-up from account-new starts at signup-name (spec account-new notes).
    @ViewBuilder private var root: some View {
        if model.start != nil {
            SignupNameView(model: model).signupColumn().task { await model.appear() }
        } else {
            OnboardingWelcomeView(model: model)
        }
    }
}

// MARK: - Shared pieces

private extension View {
    /// A centred column on wide windows. The background still fills the window.
    /// 560 points is a Bipe form-width default, not a device dimension.
    func signupColumn() -> some View {
        frame(maxWidth: 560).frame(maxWidth: .infinity)
    }
}

/// A sign-up step: title, optional body, content, the one filled button and text buttons under it.
private struct StepLayout<Content: View>: View {
    let screen: BipeScreen
    let title: String
    var message: String?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(title)
                        .font(.largeTitle.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                    if let message {
                        Text(message)
                            .font(.body)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    content
                }
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityIdentifier(screen.rawValue)
    }
}

/// The one filled button of a step.
private struct PrimaryButton: View {
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title).opacity(busy ? 0 : 1)
                if busy {
                    ProgressView().tint(BipeColor.inkOnAccent)
                }
            }
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(enabled ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
            .background(RoundedRectangle(cornerRadius: 14).fill(enabled ? BipeColor.accentFill : BipeColor.surfaceSunken))
        }
        .disabled(!enabled || busy)
    }
}

/// A text button under the filled button.
private struct TextButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.semibold))
                .foregroundStyle(BipeColor.accentInk)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
    }
}

private struct FieldFooter: View {
    let text: String
    var isError = false

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(isError ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
    }
}

private struct BipeTextField: View {
    let label: String
    let text: String
    let onChange: (String) -> Void
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var isCode = false

    var body: some View {
        TextField(label, text: Binding(get: { text }, set: onChange))
            .keyboardType(keyboard)
            .textContentType(contentType)
            .textInputAutocapitalization(isCode ? .characters : keyboard == .emailAddress ? .never : .words)
            .autocorrectionDisabled(isCode || keyboard == .emailAddress)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
            .foregroundStyle(BipeColor.inkPrimary)
    }
}

// MARK: - onboarding-welcome

struct OnboardingWelcomeView: View {
    @ObservedObject var model: SignupFlowModel
    @State private var showsSite = false
    /// The wordmark follows the system text size.
    @ScaledMetric(relativeTo: .largeTitle) private var wordmarkSize: CGFloat = 52

    var body: some View {
        // The window decides the layout, never the device.
        GeometryReader { window in
            let insets = window.safeAreaInsets
            if window.size.width >= 600 && window.size.width > window.size.height {
                // Wide (iPad, a foldable, landscape): the cover is the left half of the whole window, so the
                // split meets the fold. The window here excludes the safe area, so subtract the leading inset.
                let half = (window.size.width + insets.leading + insets.trailing) / 2
                HStack(spacing: 0) {
                    cover(edges: [.top, .bottom, .leading])
                        .frame(width: max(0, half - insets.leading))
                    actions
                        .frame(maxWidth: 400)
                        .padding(40)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                // Tall: the cover takes everything above the two buttons.
                VStack(spacing: 0) {
                    cover(edges: [.top, .horizontal])
                    actions
                        .frame(maxWidth: 480)
                        .padding(24)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.onboardingWelcome.rawValue)
        .task { await model.appear() }
        // Action learnMore: the site in the in-app browser, so sign-up stays where it was.
        .sheet(isPresented: $showsSite) {
            SafariPage(url: BipeLegalLinks.homeURL(site: AppConfig.publicSite))
                .ignoresSafeArea()
        }
    }

    /// The full-bleed açaí cover of the spec: what Bipe is. Mark and wordmark, the title and
    /// learnMore over a faint texture of Bipe marks (identity.md: Brazilian in texture, warm açaí, never flag
    /// colours). The colour runs under the system bars on `edges`; the text stays in the safe area.
    private func cover(edges: Edge.Set) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // The horizontal lockup of artwork-brief.md: mark, then the lowercase wordmark.
            HStack(spacing: 12) {
                BipeMark()
                    .frame(width: 40, height: 40)
                    .accessibilityHidden(true)
                Text(BipeStrings.appName.lowercased())
                    .font(.system(size: wordmarkSize, weight: .heavy, design: .rounded))
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 24)
            Text(title)
                .font(.system(.largeTitle, design: .rounded).weight(.bold))
                .minimumScaleFactor(0.7)
                .fixedSize(horizontal: false, vertical: true)
            Button { showsSite = true } label: {
                HStack(spacing: 4) {
                    Text(BipeStrings.onboardingLearnMore)
                    PhosphorIcon.caretRight.image
                        .resizable()
                        .frame(width: 14, height: 14)
                        .accessibilityHidden(true)
                }
                .font(.subheadline.weight(.semibold))
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .foregroundStyle(BipeColor.inkOnAccent)
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            ZStack {
                LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                BubbleTexture().accessibilityHidden(true)
            }
            .ignoresSafeArea(edges: edges)
        }
    }

    /// Two choices of equal width: new here (the variant's filled button) or back to an existing account.
    private var actions: some View {
        VStack(spacing: 12) {
            if model.variant == .loading {
                // Keeps the height of the two buttons, so the cover does not jump when the variant arrives.
                ProgressView().frame(maxWidth: .infinity, minHeight: 112)
            } else {
                let start = model.startVariant
                PrimaryButton(title: start.primary.title, busy: model.checkingInvite) { Task { await model.primary() } }
                ForEach(start.text, id: \.titleKey) { button in
                    // haveAccount: recovery-start (bipe-chat/bipe#556).
                    SecondaryButton(title: button.title) { model.onHaveAccount() }
                }
            }
        }
    }

    /// The title of the start variant; invited names the inviter.
    private var title: String {
        let start = model.startVariant
        if start == .invited, let handle = model.invite?.inviterHandle {
            return BipeStrings.onboardingInvitedBy("@" + handle)
        }
        return BipeLocalized.string(start.titleKey)
    }
}

/// The second choice next to a filled button: same size, açaí wash.
private struct SecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .foregroundStyle(BipeColor.accentInk)
                .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.accentWash))
        }
    }
}

/// Bipe marks at low contrast over the cover: the bubble-and-notch motif of identity.md, not a pattern of objects.
private struct BubbleTexture: View {
    private struct Bubble {
        let x: CGFloat, y: CGFloat, size: CGFloat, opacity: Double
    }

    /// Positions as fractions of the panel, so the texture follows the window size.
    private let bubbles = [
        Bubble(x: 0.86, y: 0.22, size: 0.50, opacity: 0.12),
        Bubble(x: 0.42, y: 0.36, size: 0.20, opacity: 0.07),
        Bubble(x: 1.00, y: 0.66, size: 0.30, opacity: 0.09),
        Bubble(x: 0.62, y: 0.44, size: 0.10, opacity: 0.12),
    ]

    var body: some View {
        GeometryReader { panel in
            let side = min(panel.size.width, panel.size.height * 1.4)
            ForEach(bubbles.indices, id: \.self) { index in
                let bubble = bubbles[index]
                // Upright only: the notch stays top-leading, as on the icon.
                BipeMark()
                    .fill(BipeColor.inkOnAccent.opacity(bubble.opacity))
                    .frame(width: side * bubble.size, height: side * bubble.size)
                    .position(x: panel.size.width * bubble.x, y: panel.size.height * bubble.y)
            }
        }
    }
}

// MARK: - signup-invite-code

struct SignupInviteCodeView: View {
    @ObservedObject var model: SignupFlowModel

    var body: some View {
        let step = BipeOnboardingStep.inviteCode
        StepLayout(screen: .signupInviteCode,
                   title: BipeLocalized.string(step.titleKey),
                   message: step.bodyKey.map { BipeLocalized.string($0) }) {
            BipeTextField(label: BipeStrings.signupInviteCode, text: model.code, onChange: model.editCode, isCode: true)
                .textInputAutocapitalization(.characters)
            // Action pasteCode: the system paste button reads the clipboard only on a tap, with no paste prompt,
            // and continues at once. A whole invite link or message is fine.
            PasteButton(payloadType: String.self) { texts in
                guard let text = texts.first else { return }
                Task { @MainActor in await model.pasteCode(text) }
            }
            .tint(BipeColor.accentFill)
            .disabled(model.inviteCodePhase == .checking)
            if model.inviteCodePhase == .checking {
                FieldFooter(text: BipeStrings.appInviteChecking)
            } else if let error = model.inviteCodeError {
                FieldFooter(text: error, isError: true)
            }
            PrimaryButton(title: step.primary.title,
                          enabled: !model.code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                          busy: model.inviteCodePhase == .checking) {
                Task { await model.submitCode() }
            }
            ForEach(step.text, id: \.titleKey) { button in
                TextButton(title: button.title) { model.noInvite() }
            }
        }
    }
}

// MARK: - signup-waitlist

struct SignupWaitlistView: View {
    @ObservedObject var model: SignupFlowModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Spacer()
                Button { model.closeWaitlist() } label: {
                    Text(BipeStrings.appClose).foregroundStyle(BipeColor.accentInk)
                }
            }
            Text(BipeLocalized.string(BipeOnboarding.waitlistBodyKey))
                .foregroundStyle(BipeColor.inkPrimary)
            if model.waitlistPhase == .done {
                Text(BipeLocalized.string(BipeOnboarding.waitlistDoneKey))
                    .font(.headline)
                    .foregroundStyle(BipeColor.statusSuccess)
            } else {
                BipeTextField(label: BipeStrings.signupEmail, text: model.waitlistEmail, onChange: model.editWaitlistEmail,
                              keyboard: .emailAddress, contentType: .emailAddress)
                if model.waitlistEmailInvalid {
                    FieldFooter(text: BipeStrings.signupEmailInvalid, isError: true)
                } else if let error = model.waitlistError {
                    FieldFooter(text: error, isError: true)
                }
                PrimaryButton(title: BipeOnboarding.waitlistPrimary.title, busy: model.waitlistPhase == .sending) {
                    Task { await model.joinWaitlist() }
                }
            }
            Spacer()
        }
        .padding(24)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.signupWaitlist.rawValue)
        .presentationDetents([.medium, .large])
    }
}

// MARK: - signup-name

struct SignupNameView: View {
    @ObservedObject var model: SignupFlowModel

    var body: some View {
        let step = BipeOnboardingStep.name
        StepLayout(screen: .signupName, title: BipeLocalized.string(step.titleKey)) {
            if let handle = model.invite?.inviterHandle, model.invite?.isValid == true {
                Text(BipeStrings.signupInvitedBy(handle))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.accentInk)
            }
            BipeTextField(label: model.isCompany ? BipeStrings.companyName : BipeStrings.signupDisplayName,
                          text: model.displayName, onChange: model.editName,
                          contentType: model.isCompany ? UITextContentType.organizationName : .name)
            FieldFooter(text: BipeStrings.signupDisplayNameFooter)
            termsRow
            if model.showTermsRequired {
                FieldFooter(text: BipeStrings.signupTermsRequired, isError: true)
            }
            PrimaryButton(title: step.primary.title, enabled: model.canContinueName) { model.continueFromName() }
        }
    }

    /// Each link text opens its page in the browser; the box flips the acceptance (ADR 0113).
    private var termsRow: some View {
        HStack(alignment: .top, spacing: 10) {
            Button { model.toggleTerms() } label: {
                RoundedRectangle(cornerRadius: 5)
                    .strokeBorder(model.termsAccepted ? BipeColor.accentFill : BipeColor.lineStrong, lineWidth: 2)
                    .background(RoundedRectangle(cornerRadius: 5).fill(model.termsAccepted ? BipeColor.accentFill : Color.clear))
                    .frame(width: 22, height: 22)
            }
            .accessibilityLabel(BipeStrings.signupTermsAccept(BipeStrings.signupTermsLink, BipeStrings.signupPrivacyLink))
            .accessibilityAddTraits(model.termsAccepted ? .isSelected : [])
            Text(TermsText.attributed)
                .foregroundStyle(BipeColor.inkPrimary)
                .tint(BipeColor.accentInk)
        }
    }
}

// MARK: - signup-pin

struct SignupPinView: View {
    @ObservedObject var model: SignupFlowModel
    let confirm: Bool
    @FocusState private var focused: Bool

    var body: some View {
        StepLayout(screen: .signupPin,
                   title: confirm ? BipeStrings.pinConfirmTitle : BipeStrings.pinCreateTitle,
                   message: confirm ? nil : BipeStrings.pinCreateBody(String(BipeRegistries.pinLength))) {
            ZStack {
                // The system digit keyboard (spec: the fallback of the number pad). It brings delete and paste.
                TextField("", text: Binding(get: { model.pin }, set: model.editPin))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .focused($focused)
                    .opacity(0.02)
                    .accessibilityHidden(true)
                HStack(spacing: 10) {
                    ForEach(0..<BipeRegistries.pinLength, id: \.self) { index in
                        Circle()
                            .fill(index < model.pin.count ? BipeColor.accentFill : BipeColor.surfaceSunken)
                            .frame(width: 14, height: 14)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
                .onTapGesture { focused = true }
                // The field is hidden from VoiceOver: the dots are the PIN field, a tap opens the keyboard (bipe#495).
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(confirm ? BipeStrings.pinConfirmTitle : BipeStrings.pinCreateTitle)
                .accessibilityValue(BipeStrings.a11yPinProgress(String(model.pin.count), String(BipeRegistries.pinLength)))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { focused = true }
            }
            if let problem = problemText {
                FieldFooter(text: problem, isError: true)
            }
            FieldFooter(text: BipeStrings.pinNeverAsked)
        }
        .onAppear { focused = true }
    }

    private var problemText: String? {
        switch model.pinProblem {
        case .none: nil
        case .repeatedDigit: BipeStrings.pinProblemRepeatedDigit
        case .sequence: BipeStrings.pinProblemSequence
        case .date: BipeStrings.pinProblemDate
        case .mismatch: BipeStrings.pinMismatch
        }
    }
}

// MARK: - signup-fallback

struct SignupFallbackView: View {
    @ObservedObject var model: SignupFlowModel

    var body: some View {
        let step = BipeOnboardingStep.fallback
        StepLayout(screen: .signupFallback,
                   title: BipeLocalized.string(step.titleKey),
                   message: step.bodyKey.map { BipeLocalized.string($0) }) {
            switch model.fallbackPhase {
            case .choose:
                PrimaryButton(title: step.primary.title) { model.addEmail() }
                // ADR 0096: the platform's own provider next to the e-mail, when the server offers it.
                if model.fallbackLinkProvider == .apple {
                    TextButton(title: BipeStrings.linkApple) { Task { await model.linkAccount() } }
                }
                ForEach(step.text, id: \.titleKey) { button in
                    TextButton(title: button.title) { Task { await model.skipEmail() } }
                }
            case .email:
                BipeTextField(label: BipeStrings.signupEmailOptional, text: model.fallbackEmail, onChange: model.editFallbackEmail,
                              keyboard: .emailAddress, contentType: .emailAddress)
                FieldFooter(text: model.fallbackEmailInvalid ? BipeStrings.signupEmailInvalid : BipeStrings.signupEmailOptionalFooter,
                            isError: model.fallbackEmailInvalid)
                PrimaryButton(title: BipeStrings.actionContinue) { Task { await model.submitEmail() } }
                ForEach(step.text, id: \.titleKey) { button in
                    TextButton(title: button.title) { Task { await model.skipEmail() } }
                }
            }
        }
        .task { await model.loadFallbackLink() }
    }
}

// MARK: - signup-create

struct SignupCreateView: View {
    @ObservedObject var model: SignupFlowModel

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            if model.createPhase == .failed {
                PhosphorIcon.warningCircle.image
                    .resizable()
                    .frame(width: 48, height: 48)
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .accessibilityHidden(true)
                Text(model.createErrorText)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                if model.canFix {
                    PrimaryButton(title: BipeStrings.actionContinue) { model.fix() }
                } else {
                    PrimaryButton(title: BipeStrings.appRetry) { Task { await model.retryCreate() } }
                }
            } else {
                ProgressView()
                Text(BipeStrings.appSignupCreating)
                    .foregroundStyle(BipeColor.inkSecondary)
                Spacer()
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier(BipeScreen.signupCreate.rawValue)
    }
}
