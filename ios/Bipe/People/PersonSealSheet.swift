// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec person-seal: how a person's name was checked, and what the seal does not promise (ADR 0275).
struct PersonSealSheet: View {
    let proof: BipePersonProof
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 10) {
                        (proof.isFill ? PhosphorIcon.sealCheckFill : PhosphorIcon.sealCheck).image
                            .resizable()
                            .frame(width: 40, height: 40)
                            .foregroundStyle(BipeColor.trustVerifiedInk)
                            .accessibilityHidden(true)
                        Text(proof.line)
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                }
                Section {
                    fact(BipeStrings.badgeVerified, proof.payload.name)
                    fact(BipeStrings.personSealScheme, proof.schemeName)
                    fact(BipeStrings.personSealChecked, month(proof.payload.iat))
                    fact(BipeStrings.personSealValidUntil, month(proof.payload.exp))
                } footer: {
                    Text(BipeStrings.personSealBody)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.personSeal.rawValue)
        .presentationDetents([.medium, .large])
    }

    /// Spec checkedAt and validUntil: the month and the year, never the day.
    private func month(_ seconds: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(seconds)).formatted(.dateTime.month(.wide).year())
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
