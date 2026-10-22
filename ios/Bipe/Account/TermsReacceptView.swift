// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec terms-reaccept (bipe-shared product/screens/terms-reaccept.json).
@MainActor
final class TermsReacceptModel: ObservableObject {
    enum Phase: Equatable {
        case hidden
        case shown
        case saving
        case failed
    }

    @Published private(set) var phase: Phase = .hidden
    @Published private(set) var accepted = false
    @Published private(set) var acceptedVersion: String?
    @Published private(set) var currentVersion = BipeRegistries.legalVersion

    private let api: BipeAPIClient
    private let session: AccountSession

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
    }

    /// Action check: after sign-in and on foreground. A failed read shows nothing; the next check tries again.
    func check() async {
        guard phase == .hidden else { return }
        if let config = try? await api.signupConfig(), let version = config.termsVersion, version > currentVersion {
            currentVersion = version
        }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await self.api.account(accessToken: token)
        }
        guard let status else { return }
        acceptedVersion = status.termsVersion
        if status.termsVersion != currentVersion {
            accepted = false
            phase = .shown
        }
    }

    /// Action toggle.
    func toggle() {
        accepted.toggle()
    }

    /// Action accept: never sends an acceptance the person did not tick.
    func accept() async {
        guard accepted else { return }
        phase = .saving
        do {
            let version = currentVersion
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await self.api.acceptTerms(version: version, accessToken: token)
            }
            phase = .hidden
        } catch {
            phase = .failed
        }
    }
}

struct TermsReacceptView: View {
    @ObservedObject var model: TermsReacceptModel
    let onSignOut: () -> Void
    @State private var confirmsSignOut = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.termsReacceptTitle)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.termsReacceptBody)
                .foregroundStyle(BipeColor.inkSecondary)
            HStack(alignment: .top, spacing: 10) {
                Button { model.toggle() } label: {
                    RoundedRectangle(cornerRadius: 5)
                        .strokeBorder(model.accepted ? BipeColor.accentFill : BipeColor.lineStrong, lineWidth: 2)
                        .background(RoundedRectangle(cornerRadius: 5).fill(model.accepted ? BipeColor.accentFill : Color.clear))
                        .frame(width: 22, height: 22)
                }
                .accessibilityLabel(BipeStrings.signupTermsAccept(BipeStrings.signupTermsLink, BipeStrings.signupPrivacyLink))
                .accessibilityAddTraits(model.accepted ? .isSelected : [])
                Text(TermsText.attributed)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .tint(BipeColor.accentInk)
            }
            if model.phase == .failed {
                Text(BipeStrings.termsReacceptFailed)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            Spacer()
            Button {
                Task { await model.accept() }
            } label: {
                Text(BipeStrings.actionContinue)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .foregroundStyle(model.accepted ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                    .background(RoundedRectangle(cornerRadius: 14).fill(model.accepted ? BipeColor.accentFill : BipeColor.surfaceSunken))
            }
            .disabled(!model.accepted || model.phase == .saving)
            Button { confirmsSignOut = true } label: {
                Text(BipeStrings.accountSignOut)
                    .foregroundStyle(BipeColor.accentInk)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.termsReaccept.rawValue)
        .confirmationDialog(BipeStrings.accountSignOut, isPresented: $confirmsSignOut) {
            Button(role: .destructive, action: onSignOut) { Text(BipeStrings.accountSignOut) }
            Button(role: .cancel) { } label: { Text(BipeStrings.appCancel) }
        }
    }
}

/// The clickwrap sentence with its two links (ADR 0113). signup-name and terms-reaccept show the same text.
enum TermsText {
    static var attributed: AttributedString {
        let terms = BipeStrings.signupTermsLink
        let privacy = BipeStrings.signupPrivacyLink
        var text = AttributedString(BipeStrings.signupTermsAccept(terms, privacy))
        if let range = text.range(of: terms) {
            text[range].link = BipeLegalLinks.termsURL(site: AppConfig.publicSite)
        }
        if let range = text.range(of: privacy) {
            text[range].link = BipeLegalLinks.privacyURL(site: AppConfig.publicSite)
        }
        return text
    }
}
