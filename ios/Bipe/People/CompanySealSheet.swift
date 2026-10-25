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

    /// Spec company-seal cvmLines: the CVM line of a verified seal (ADR 0077), from the signed badge only.
    var cvm: BipeCvmLines.Result {
        if case .verified(let payload) = self { BipeCvmLines.lines(payload.cvm) } else { BipeCvmLines.lines(nil) }
    }
}

extension BipeCvmLines.Line {
    var text: String {
        switch key {
        case .registered: BipeStrings.cvmRegistered(arg ?? "")
        case .cancelled: BipeStrings.cvmCancelled(arg ?? "")
        case .noRegistration: BipeStrings.cvmNoRegistration
        case .bankNote: BipeStrings.cvmBankNote
        case .stopOrder: BipeStrings.cvmStopOrder(arg ?? "")
        }
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
                if case .verified = state, !state.cvm.lines.isEmpty {
                    cvmSection(state.cvm)
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
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
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

    /// cvmDanger: a stop order is red with a filled icon and sits above the other facts. The rest is neutral.
    private func cvmSection(_ cvm: BipeCvmLines.Result) -> some View {
        Section {
            ForEach(Array(cvm.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .top, spacing: 8) {
                    if cvm.danger {
                        PhosphorIcon.warningCircle.image
                            .resizable()
                            .frame(width: 18, height: 18)
                            .foregroundStyle(BipeColor.trustDangerInk)
                            .accessibilityHidden(true)
                    }
                    Text(line.text)
                        .font(cvm.danger ? .body.bold() : .body)
                        .foregroundStyle(cvm.danger ? BipeColor.trustDangerInk : BipeColor.inkPrimary)
                }
            }
        } footer: {
            Text(BipeStrings.cvmSource)
        }
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
