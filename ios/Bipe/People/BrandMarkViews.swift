// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

extension BipeBrandMark {
    /// The fixed words of the mark with the brand's name (registries/brand-marks.json). `nil` for a label this build
    /// does not know; the verifier already refuses one.
    var line: String? {
        switch label {
        case "revendedora": BipeStrings.brandMarkLabelRevendedora(brandName)
        case "revendedor": BipeStrings.brandMarkLabelRevendedor(brandName)
        case "corretor": BipeStrings.brandMarkLabelCorretor(brandName)
        case "representante": BipeStrings.brandMarkLabelRepresentante(brandName)
        default: nil
        }
    }
}

extension Array where Element == BipeBrandMark {
    /// Spec brand-mark: the first mark's line, then "+N" for the others.
    var summary: String? {
        guard let first = first?.line else { return nil }
        return count > 1 ? "\(first) \(BipeStrings.brandMarkMore((count - 1).formatted()))" : first
    }
}

/// Spec brand-mark: the line next to a reseller's own name, or the ended notice once. Never a seal icon on the
/// reseller (ADR 0108 point 3). A tap opens the sheet.
struct BrandMarkLine: View {
    let userId: String?
    let marks: BipeBrandMarks?
    var alignment: HorizontalAlignment = .center
    @State private var showsSheet = false

    var body: some View {
        if let userId, let marks {
            let list = marks.marks(for: userId)
            if let summary = list.summary {
                Button {
                    showsSheet = true
                } label: {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(alignment == .leading ? .leading : .center)
                }
                .buttonStyle(.plain)
                .accessibilityHint(BipeStrings.brandMarkTitle)
                .sheet(isPresented: $showsSheet) {
                    BrandMarkSheet(marks: list)
                }
            } else if let brand = marks.endedBrand(for: userId) {
                HStack(spacing: 6) {
                    PhosphorIcon.info.image
                        .resizable()
                        .frame(width: 16, height: 16)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .accessibilityHidden(true)
                    Text(BipeStrings.brandMarkEnded(brand))
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustCautionInk)
                }
            }
        }
    }
}

/// Spec brand-mark, chat header: the summary or the ended notice as text. The title is the button that opens
/// person-profile, where the line opens the sheet.
struct BrandMarkHeaderLine: View {
    let userId: String?
    let marks: BipeBrandMarks?

    var body: some View {
        if let userId, let marks {
            if let summary = marks.marks(for: userId).summary {
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .lineLimit(1)
            } else if let brand = marks.endedBrand(for: userId) {
                Text(BipeStrings.brandMarkEnded(brand))
                    .font(.caption)
                    .foregroundStyle(BipeColor.trustCautionInk)
                    .lineLimit(1)
            }
        }
    }
}

/// Spec brand-mark, chat list row: the summary on one short line, no button (the row opens the chat).
struct BrandMarkCaption: View {
    let userId: String?
    @Environment(\.bipeBrandMarks) private var marks

    var body: some View {
        if let userId, let summary = marks?.marks(for: userId).summary {
            Text(summary)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
                .lineLimit(1)
        }
    }
}

/// Spec brand-mark: each mark with its brand's own seal, and what the mark does not mean.
struct BrandMarkSheet: View {
    let marks: [BipeBrandMark]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        NavigationStack {
            List {
                ForEach(marks) { mark in
                    Section {
                        if let line = mark.line {
                            Text(line)
                                .font(.headline)
                                .foregroundStyle(BipeColor.inkPrimary)
                        }
                        brandSeal(mark)
                    }
                }
                Section {
                    Text(BipeStrings.brandMarkBody)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            .navigationTitle(BipeStrings.brandMarkTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.brandMark.rawValue)
        .presentationDetents([.medium, .large])
    }

    /// brandSeal verified: the brand's own cnpj badge verifies and names the mark's CNPJ. Else unknown.
    @ViewBuilder
    private func brandSeal(_ mark: BipeBrandMark) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(BipeStrings.brandMarkBrandSeal)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
            if let badge = seals?.badge(for: mark.brand), badge.cnpj == mark.cnpj {
                HStack(spacing: 6) {
                    PhosphorIcon.sealCheck.image
                        .resizable()
                        .frame(width: 16, height: 16)
                        .foregroundStyle(BipeColor.trustVerifiedInk)
                        .accessibilityHidden(true)
                    Text(BipeStrings.badgeCompanyCnpj + " " + BipeCNPJ.formatted(mark.cnpj))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .textSelection(.enabled)
                }
                .accessibilityElement(children: .combine)
            } else {
                Text(BipeStrings.brandMarkBrandUnchecked)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
    }
}
