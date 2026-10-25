// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Specs trust-card and trust-profile (ADR 0019, ADR 0112): the private Perfil de confiança of the current account.
/// Steps come only from ``BipeTrustProfile``. Only a company account calls the API (`GET /v1/me/business`).
@MainActor
final class TrustCardModel: ObservableObject {
    @Published private(set) var business: BipeBusinessClaim?
    private var loadedUserId: String?

    /// Fills the company steps. A failed or offline call keeps them "Em breve"; the card never shows an error.
    func load(session: AccountSession, api: BipeAPIClient, isCompany: Bool) async {
        if loadedUserId != session.userId {
            loadedUserId = session.userId
            business = nil
        }
        guard isCompany else { return }
        let claim = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeBusinessClaim? in
            try await api.myBusiness(accessToken: token)
        }
        guard loadedUserId == session.userId, let claim else { return }
        business = claim
    }

    func profile(for account: AccountSummary) -> BipeTrustProfile {
        BipeTrustProfile(displayName: account.displayName,
                         hasAvatar: account.avatarUrl != nil,
                         handle: account.handle,
                         accountType: account.isCompany ? .company : .personal,
                         business: loadedUserId == account.userId ? business : nil,
                         claimEnabled: true)
    }
}

/// What the trust rows can open. Each one is a screen that completes step 2.
struct TrustActions {
    let addName: () -> Void
    let addPhoto: () -> Void
    let chooseHandle: () -> Void
    /// Company account: opens business-claim (bipe-chat/bipe#249).
    var openClaim: (() -> Void)?

    func run(_ item: BipeTrustProfile.ProfileItem) {
        switch item {
        case .displayName: addName()
        case .handle: chooseHandle()
        case .avatar: addPhoto()
        }
    }
}

/// Spec trust-card: the card after Anotações. One row when every live step is done.
struct TrustCardSection: View {
    let profile: BipeTrustProfile
    let actions: TrustActions
    let openLadder: () -> Void

    var body: some View {
        Section {
            Button(action: openLadder) {
                HStack(spacing: 12) {
                    PhosphorIcon.fingerprint.image.resizable().frame(width: 24, height: 24)
                        .foregroundStyle(BipeColor.accentInk)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(BipeStrings.trustTitle).foregroundStyle(BipeColor.inkPrimary)
                        if !profile.liveStepsDone {
                            ProgressView(value: profile.progress).tint(BipeColor.accentFill)
                        }
                        Text(TrustProgress.text(profile)).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    PhosphorIcon.caretRight.image.resizable().frame(width: 14, height: 14)
                        .foregroundStyle(BipeColor.glyphSecondary)
                }
            }
            .accessibilityIdentifier(BipeScreen.trustCard.rawValue)
            if !profile.liveStepsDone {
                ForEach(profile.missingProfileItems, id: \.self) { item in
                    Button { actions.run(item) } label: {
                        IconLabel(icon: TrustText.icon(item), text: TrustText.action(item, isCompany: profile.isCompany),
                                  tint: BipeColor.accentInk)
                    }
                }
            }
        } footer: {
            if !profile.liveStepsDone { Text(BipeStrings.trustFooter) }
        }
    }
}

/// Spec trust-profile: every step with what it proves and its status.
struct TrustProfileView: View {
    let profile: BipeTrustProfile
    let actions: TrustActions

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    ProgressView(value: profile.progress).tint(BipeColor.accentFill)
                    Text(TrustProgress.text(profile)).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
                .padding(.vertical, 4)
            }
            Section {
                ForEach(Array(profile.steps.enumerated()), id: \.element.id) { index, step in
                    if let openClaim = actions.openClaim, profile.isCompany, [.cnpj, .proof, .domain].contains(step.id) {
                        // Action openClaim: every company step opens business-claim, done or not.
                        Button(action: openClaim) {
                            TrustStepRow(number: index + 1, step: step, isCompany: profile.isCompany)
                        }
                    } else {
                        TrustStepRow(number: index + 1, step: step, isCompany: profile.isCompany)
                    }
                    if step.id == .profile, step.status == .todo {
                        ForEach(profile.missingProfileItems, id: \.self) { item in
                            Button { actions.run(item) } label: {
                                IconLabel(icon: TrustText.icon(item), text: TrustText.action(item, isCompany: profile.isCompany),
                                          tint: BipeColor.accentInk)
                            }
                            .padding(.leading, 36)
                        }
                    }
                }
            } footer: {
                Text(BipeStrings.trustFooter)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BipeStrings.trustTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.trustProfile.rawValue)
    }
}

private struct TrustStepRow: View {
    let number: Int
    let step: BipeTrustProfile.Step
    let isCompany: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.subheadline.monospacedDigit().bold())
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(width: 24, alignment: .center)
            VStack(alignment: .leading, spacing: 2) {
                Text(TrustText.title(step.id)).foregroundStyle(BipeColor.inkPrimary)
                Text(TrustText.body(step.id, isCompany: isCompany)).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                if step.status == .comingSoon {
                    Text(BipeStrings.trustComingSoon).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
            if step.status == .done {
                // A check, never the Bipe seal (trust-ui.md): the status has an icon, not only a colour.
                PhosphorIcon.checkCircle.image.resizable().frame(width: 22, height: 22)
                    .foregroundStyle(BipeColor.statusSuccess)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(step.status == .done ? BipeStrings.trustStepDone : "")
    }
}

private enum TrustProgress {
    static func text(_ profile: BipeTrustProfile) -> String {
        BipeStrings.trustProgress(String(profile.completedCount), String(profile.totalCount))
    }
}

private enum TrustText {
    static func title(_ id: BipeTrustProfile.StepID) -> String {
        switch id {
        case .account: BipeStrings.trustStepAccount
        case .profile: BipeStrings.trustStepProfile
        case .email: BipeStrings.trustStepEmail
        case .pix: BipeStrings.trustStepPix
        case .govbr: BipeStrings.trustStepGovbr
        case .cnpj: BipeStrings.trustStepCnpj
        case .proof: BipeStrings.trustStepProof
        case .domain: BipeStrings.trustStepDomain
        }
    }

    static func body(_ id: BipeTrustProfile.StepID, isCompany: Bool) -> String {
        switch id {
        case .account: BipeStrings.trustStepAccountBody
        case .profile: isCompany ? BipeStrings.trustStepCompanyProfileBody : BipeStrings.trustStepProfileBody
        case .email: BipeStrings.trustStepEmailBody
        case .pix: BipeStrings.trustStepPixBody
        case .govbr: BipeStrings.trustStepGovbrBody
        case .cnpj: BipeStrings.trustStepCnpjBody
        case .proof: BipeStrings.trustStepProofBody
        case .domain: BipeStrings.trustStepDomainBody
        }
    }

    static func action(_ item: BipeTrustProfile.ProfileItem, isCompany: Bool) -> String {
        switch item {
        case .displayName: isCompany ? BipeStrings.trustStepProfileAddCompanyName : BipeStrings.trustStepProfileAddName
        case .handle: BipeStrings.trustStepProfileAddHandle
        case .avatar: isCompany ? BipeStrings.trustStepProfileAddLogo : BipeStrings.trustStepProfileAddPhoto
        }
    }

    static func icon(_ item: BipeTrustProfile.ProfileItem) -> PhosphorIcon {
        switch item {
        case .displayName: .pencilSimple
        case .handle: .userCircle
        case .avatar: .camera
        }
    }
}
