// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

extension BipeCompanySealState {
    /// The notice for a seal that is no longer valid, else `nil` (spec company-seal).
    var notice: String? {
        switch self {
        case .revoked(.notActive), .revoked(.notFound): BipeStrings.badgeCompanyRevokedNotActive
        case .revoked: BipeStrings.badgeCompanyRevoked
        case .suspended: BipeStrings.badgeCompanySuspended
        case .verified, .none: nil
        }
    }

    var isVerified: Bool {
        if case .verified = self { true } else { false }
    }
}

/// Spec company-seal: the facts behind a company's seal, and what the seal does not promise (ADR 0042).
struct CompanySealSheet: View {
    let userId: String
    let state: BipeCompanySealState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        (state.isVerified ? PhosphorIcon.sealCheck : PhosphorIcon.sealWarning).image
                            .resizable()
                            .frame(width: 28, height: 28)
                            .foregroundStyle(state.isVerified ? BipeColor.trustVerifiedInk : BipeColor.trustCautionInk)
                            .accessibilityHidden(true)
                        Text(state.notice ?? BipeStrings.badgeCompany)
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                    }
                }
                if case .verified(let payload) = state {
                    Section {
                        if let company = payload.company {
                            fact(BipeStrings.badgeCompanyLegalName, company.legalName)
                            if let tradeName = company.tradeName {
                                fact(BipeStrings.badgeCompanyTradeName, tradeName)
                            }
                        }
                        if let cnpj = payload.cnpj {
                            fact(BipeStrings.badgeCompanyCnpj, BipeCNPJ.formatted(cnpj))
                        }
                        if let month = payload.company?.checkedMonth, let checked = BipeCNPJ.checkedDate(month) {
                            fact(BipeStrings.badgeCompanyChecked, checked.formatted(.dateTime.month(.wide).year()))
                        }
                        fact(BipeStrings.badgeCompanyValidUntil,
                             Date(timeIntervalSince1970: TimeInterval(payload.exp)).formatted(date: .long, time: .omitted))
                    } footer: {
                        Text(BipeStrings.badgeCompanyBody)
                    }
                }
            }
            .navigationTitle(BipeStrings.appSealTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.companySeal.rawValue)
        .presentationDetents([.medium, .large])
    }

    private func fact(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
            Text(value)
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
        }
    }
}
