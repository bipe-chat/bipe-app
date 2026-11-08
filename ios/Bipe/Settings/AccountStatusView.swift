// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec account-status (security.md rule 11, bipe-chat/bipe#846): the first-contact tier, the budget used, when one
/// more frees up, and the step that raises the budget. The numbers come from GET /v1/me/reach.
@MainActor
final class AccountStatusModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case shown(BipeReachStatus)
        case failed
    }

    @Published private(set) var phase: Phase = .loading

    private let settings: SettingsModel

    init(settings: SettingsModel) {
        self.settings = settings
    }

    /// Actions appear and retry.
    func appear() async {
        let api = settings.api
        do {
            let status = try await settings.authorized { (token: String) async throws(BipeAPIError) -> BipeReachStatus in
                try await api.reach(accessToken: token)
            }
            phase = .shown(status)
        } catch {
            if case .shown = phase { return }
            phase = .failed
        }
    }

    static func tierName(_ tier: BipeReachStatus.Tier) -> String {
        switch tier {
        case .unattested: BipeStrings.accountStatusTierUnattested
        case .new: BipeStrings.accountStatusTierNew
        case .normal: BipeStrings.accountStatusTierNormal
        case .verified: BipeStrings.accountStatusTierVerified
        case .company: BipeStrings.accountStatusTierCompany
        case .slowed: BipeStrings.accountStatusTierSlowed
        }
    }

    static func raiseLine(_ raiseBy: BipeReachStatus.RaiseBy?) -> String? {
        switch raiseBy {
        case .verify: BipeStrings.accountStatusRaiseVerify
        case .wait: BipeStrings.accountStatusRaiseWait
        case nil: nil
        }
    }
}

/// Spec account-status.
struct AccountStatusView: View {
    @StateObject private var model: AccountStatusModel

    init(settings: SettingsModel) {
        _model = StateObject(wrappedValue: AccountStatusModel(settings: settings))
    }

    var body: some View {
        List {
            switch model.phase {
            case .loading:
                ProgressView().frame(maxWidth: .infinity)
            case .failed:
                Section {
                    Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.trustDangerInk)
                    Button(BipeStrings.appRetry) {
                        Task { await model.appear() }
                    }
                }
            case .shown(let status):
                Section {
                    LabeledContent(BipeStrings.accountStatusTier, value: AccountStatusModel.tierName(status.tier))
                    Text(BipeStrings.accountStatusUsed(status.used.formatted(), status.budget.formatted()))
                    if let resetsAt = status.resetsAt {
                        Text(BipeStrings.accountStatusResets(resetsAt.formatted(date: .abbreviated, time: .shortened)))
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    if let raise = AccountStatusModel.raiseLine(status.raiseBy) {
                        Text(raise)
                    }
                } footer: {
                    Text(BipeStrings.accountStatusFooter)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.accountStatusTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.accountStatus.rawValue)
        .task { await model.appear() }
    }
}
