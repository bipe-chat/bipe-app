// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec linked-accounts (ADR 0096): links an Apple or a Google account as a recovery factor, shows the linked ones and
/// unlinks them. Apple through Sign in with Apple, Google through the Bipe API's web flow.
@MainActor
final class LinkedAccountsModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case shown
        case linking
        case unlinking
        case failed
    }

    struct Row: Identifiable, Equatable {
        let provider: BipeLinkProvider
        let linked: Bool
        let available: Bool

        var id: String { provider.rawValue }
    }

    @Published private(set) var rows: [Row] = []
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var error: String?
    @Published var notice: String?

    private let settings: SettingsModel

    init(settings: SettingsModel) {
        self.settings = settings
    }

    /// Action appear.
    func appear() async {
        let api = settings.api
        let providers = (try? await api.linkProviders().usableOnIOS) ?? []
        guard let account = try? await settings.authorized({ (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) else {
            phase = .failed
            error = BipeStrings.errorNetwork
            return
        }
        let linked = Set((account.links ?? []).map(\.provider))
        rows = BipeLinkProvider.allCases
            .map { Row(provider: $0, linked: linked.contains($0), available: providers.contains($0)) }
            .filter { $0.linked || $0.available }
        phase = .shown
        await settings.load()
    }

    /// Action link: the owner check, the provider sign-in, then PUT /v1/me/links/{provider}.
    func link(_ provider: BipeLinkProvider) async {
        guard await Self.confirmOwner(reason: BipeStrings.linkedAccountBiometric) else {
            if !OwnerCheck.canCheckOwner { error = BipeStrings.linkedAccountError }
            return
        }
        phase = .linking
        error = await Self.runLink(provider, settings: settings)
        if error == nil {
            await dismissOwnEvent()
        }
        phase = .shown
        await appear()
    }

    /// Action unlink, after the confirm dialog: the owner check, then DELETE /v1/me/links/{provider}.
    func unlink(_ provider: BipeLinkProvider) async {
        guard await Self.confirmOwner(reason: BipeStrings.linkedAccountUnlinkBiometric) else {
            if !OwnerCheck.canCheckOwner { error = BipeStrings.linkedAccountUnlinkError }
            return
        }
        phase = .unlinking
        error = nil
        let api = settings.api
        do {
            try await settings.authorized { (token: String) async throws(BipeAPIError) in
                try await api.unlink(provider, accessToken: token)
            }
            await dismissOwnEvent()
            notice = BipeStrings.linkedAccountUnlinked
        } catch {
            self.error = BipeStrings.linkedAccountUnlinkError
        }
        phase = .shown
        await appear()
    }

    /// The link steps after the owner check. `nil` when linked or when the person closed the sheet, else the error text.
    static func runLink(_ provider: BipeLinkProvider, settings: SettingsModel) async -> String? {
        let session = settings.session
        return await runLink(provider, api: settings.api) { session.accessToken() }
    }

    /// The same steps with an account's token reader (also signup-fallback, right after the account exists). The
    /// token retry wraps each request, never the provider sheet.
    static func runLink(_ provider: BipeLinkProvider, api: BipeAPIClient, accessToken: @escaping @MainActor () -> String?) async -> String? {
        do {
            let outcome = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { (token: String) async throws(BipeAPIError) -> LinkSignIn.Outcome in
                try await LinkSignIn.run(provider, api: api, accessToken: token)
            }
            switch outcome {
            case .proof(let proof):
                try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { (token: String) async throws(BipeAPIError) in
                    try await api.link(provider, proof: proof, accessToken: token)
                }
                return nil
            case .cancelled:
                return nil
            case .failed:
                return BipeStrings.linkedAccountError
            }
        } catch {
            switch error {
            case .server(.linkTaken, _, _): return BipeStrings.linkedAccountTaken
            case .network: return BipeStrings.errorNetwork
            default: return BipeStrings.linkedAccountError
            }
        }
    }

    /// Security rule 14, tier 3: biometrics or the passcode. A phone without a passcode cannot pass (fail closed).
    private static func confirmOwner(reason: String) async -> Bool {
        await OwnerCheck.owner(reason: reason)
    }

    /// A change made on this phone needs no recovery-alert notice here.
    private func dismissOwnEvent() async {
        let api = settings.api
        let status = try? await settings.authorized { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }
        if let newest = status?.linkEvents?.first {
            RecoveryAlertModel.dismissOnThisPhone(userId: settings.session.userId, eventIds: [newest.id])
        }
    }
}

/// Spec linked-accounts.
struct LinkedAccountsView: View {
    @StateObject private var model: LinkedAccountsModel
    @State private var confirmUnlink: BipeLinkProvider?

    init(settings: SettingsModel) {
        _model = StateObject(wrappedValue: LinkedAccountsModel(settings: settings))
    }

    var body: some View {
        List {
            Section {
                ForEach(model.rows) { row in
                    Button {
                        if row.linked {
                            confirmUnlink = row.provider
                        } else {
                            Task { await model.link(row.provider) }
                        }
                    } label: {
                        if row.linked {
                            SettingsRowLabel(title: row.provider == .apple ? BipeStrings.linkedApple : BipeStrings.linkedGoogle,
                                             value: BipeStrings.linkedAccountUnlink)
                        } else {
                            Text(row.provider == .apple ? BipeStrings.linkApple : BipeStrings.linkGoogle)
                                .foregroundStyle(BipeColor.accentInk)
                        }
                    }
                    .disabled(model.phase == .linking || model.phase == .unlinking)
                }
                if model.phase == .loading || model.phase == .linking || model.phase == .unlinking {
                    ProgressView().frame(maxWidth: .infinity)
                }
            } header: {
                Text(BipeStrings.linkedAccountBody(String(BipeRegistries.linkedRecoveryDelayHours)))
                    .textCase(nil)
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(BipeStrings.linkedAccountNoPinReset)
                    if let error = model.error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.linkedAccountsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.linkedAccounts.rawValue)
        .task { await model.appear() }
        .alert(BipeStrings.linkedAccountUnlinkTitle,
               isPresented: Binding(get: { confirmUnlink != nil }, set: { if !$0 { confirmUnlink = nil } }),
               presenting: confirmUnlink) { provider in
            Button(BipeStrings.linkedAccountUnlink, role: .destructive) {
                Task { await model.unlink(provider) }
            }
            Button(BipeStrings.appCancel, role: .cancel) { }
        } message: { _ in
            Text(BipeStrings.linkedAccountUnlinkBody)
        }
        .overlay(alignment: .bottom) {
            if let notice = model.notice {
                NoticeView(text: notice)
                    .task {
                        try? await Task.sleep(for: .seconds(2))
                        model.notice = nil
                    }
            }
        }
    }
}
