// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-new (bipe-chat/bipe#445): the invite quota of the account on screen, and the invite a tap makes.
@MainActor
final class AccountNewModel: ObservableObject {
    enum InvitePhase: Equatable {
        case loading
        case ready
        case noneLeft
        case failed
    }

    @Published private(set) var invitePhase: InvitePhase = .loading
    @Published private(set) var quotaText: String?
    @Published private(set) var busy = false
    @Published private(set) var error: String?

    let session: AccountSession
    private let api: BipeAPIClient
    private let creator: BipeInviteCreator

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        creator = BipeInviteCreator(apiClient: api, userID: session.userId, accessToken: { session.accessToken() })
    }

    /// Action appear: reads the quota. Creates nothing.
    func appear() async {
        do {
            let quota = try await creator.quota()
            if quota.available > 0 {
                invitePhase = .ready
            } else {
                noneLeft(nextAvailableAt: quota.nextAvailableAt)
            }
        } catch {
            invitePhase = .failed
        }
    }

    /// Actions choosePersonal and chooseCompany: the invite for the sign-up, else `nil` with the reason on screen.
    func choose(_ accountType: BipeAccountType) async -> SignupStart? {
        guard !busy else { return nil }
        busy = true
        error = nil
        defer { busy = false }
        do {
            switch try await creator.createIfAvailable() {
            case .created(let invite):
                return SignupStart(inviteId: invite.id, code: invite.code, accountType: accountType)
            case .noneLeft(let nextAvailableAt):
                noneLeft(nextAvailableAt: nextAvailableAt)
            }
        } catch {
            if case .server(.inviteQuotaExhausted, _, _) = error {
                noneLeft(nextAvailableAt: nil)
            } else {
                self.error = error.localizedMessage
            }
        }
        return nil
    }

    /// Action cancelSignup: gives the slot back. A failed revoke leaves a pending invite in Convites, nothing worse.
    func revoke(_ start: SignupStart) async {
        try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> Void in
            guard let inviteId = start.inviteId else { return }
            try await self.api.revokeInvite(id: inviteId, accessToken: token)
        }
    }

    private func noneLeft(nextAvailableAt: Date?) {
        invitePhase = .noneLeft
        quotaText = BipeInviteQuota.noneLeft(nextAvailableAt: nextAvailableAt)
    }
}

/// The Nova conta sheet: the three choices, then the sign-up or recovery-start in the same sheet.
struct AccountNewView: View {
    @ObservedObject var tabs: AppTabsModel
    let api: BipeAPIClient
    let close: () -> Void
    @StateObject private var model: AccountNewModel
    @State private var signup: SignupStart?
    @State private var showsExisting = false
    @State private var recoveryLink: URL?

    /// preset: a sign-up that already has its invite (invite-signed-in newAccount).
    init(tabs: AppTabsModel, session: AccountSession, api: BipeAPIClient, preset: SignupStart? = nil, close: @escaping () -> Void) {
        self.tabs = tabs
        self.api = api
        self.close = close
        _model = StateObject(wrappedValue: AccountNewModel(session: session, api: api))
        _signup = State(initialValue: preset)
    }

    var body: some View {
        if let signup {
            SignupFlowView(api: api, start: signup, onCancel: { reachedCreate in cancel(signup, reachedCreate: reachedCreate) }) { session, outcome in
                if signup.accountType == .company {
                    // The account on screen owns the new company account, on this phone only (bipe-chat/bipe#444).
                    AccountList.setOwner(model.session.userId, of: session.userId)
                }
                close()
                tabs.start.didSignUp(session, outcome: outcome)
            }
        } else if showsExisting {
            RecoveryFlowView(api: api, link: $recoveryLink, close: { showsExisting = false }) { session in
                close()
                tabs.start.didRecover(session)
            }
        } else {
            choices
        }
    }

    private var current: AccountSummary? { tabs.accounts.first(where: \.isCurrent) }

    /// canAddCompany of the spec: App Attest on this phone, and the account on screen is not a company.
    private var canAddCompany: Bool { BipeAppAttest.isSupported && current?.isCompany == false }

    private var invitesLeft: Bool { model.invitePhase != .noneLeft }

    private var choices: some View {
        NavigationStack {
            List {
                Section {
                    choice(icon: .userCircle, title: BipeStrings.accountNewPersonal,
                           body: tabs.canAddAccount ? BipeStrings.accountNewPersonalBody
                               : BipeStrings.accountCapReached(String(tabs.maxPersonalAccounts)),
                           enabled: tabs.canAddAccount && invitesLeft) {
                        choose(.personal)
                    }
                    if canAddCompany {
                        choice(icon: .handshake, title: BipeStrings.accountNewCompany,
                               body: current.map { "\(BipeStrings.accountNewCompanyBody) \(BipeStrings.accountNewCompanyOwner($0.displayName))" }
                                   ?? BipeStrings.accountNewCompanyBody,
                               enabled: invitesLeft) {
                            choose(.company)
                        }
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let text = model.quotaText, model.invitePhase == .noneLeft {
                            Text(text)
                        } else if let name = current?.displayName {
                            Text(BipeStrings.accountNewInviteNote(name))
                        }
                        if let error = model.error {
                            Text(error).foregroundStyle(BipeColor.trustDangerInk)
                        }
                    }
                }
                Section {
                    choice(icon: .key, title: BipeStrings.accountNewExisting, body: BipeStrings.accountNewExistingBody,
                           enabled: true) {
                        showsExisting = true
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.accountNewTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(BipeStrings.appCancel, action: close) }
                if model.busy {
                    ToolbarItem(placement: .principal) { ProgressView() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.accountNew.rawValue)
        .task { await model.appear() }
    }

    private func choice(icon: PhosphorIcon, title: String, body: String, enabled: Bool,
                        action: @escaping () -> Void) -> some View {
        let on = enabled && !model.busy
        return Button(action: action) {
            HStack(spacing: 12) {
                icon.image.resizable().frame(width: 24, height: 24)
                    .foregroundStyle(on ? BipeColor.accentInk : BipeColor.inkDisabled)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(on ? BipeColor.inkPrimary : BipeColor.inkDisabled)
                    Text(body).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            .padding(.vertical, 4)
        }
        .disabled(!on)
        .listRowBackground(BipeColor.surfaceRaised)
    }

    private func choose(_ accountType: BipeAccountType) {
        Task {
            if let start = await model.choose(accountType) {
                signup = start
            }
        }
    }

    /// Action cancelSignup: before signup-create the invite goes back to the account on screen. After it, the
    /// invite may already hold the new account (ADR 0094), so it stays.
    private func cancel(_ start: SignupStart, reachedCreate: Bool) {
        close()
        guard !reachedCreate, start.isOwnInvite else { return }
        Task { await model.revoke(start) }
    }
}
