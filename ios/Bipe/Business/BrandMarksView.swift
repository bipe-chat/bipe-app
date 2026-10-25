// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Spec brand-marks (ADR 0162, bipe-chat/bipe#486): on the Trabalho tab, a reseller accepts, refuses and removes a
/// brand's mark; a brand with a verified seal makes codes and ends the marks it gave.
struct BrandMarksView: View {
    let api: BipeBusinessAPI
    @ObservedObject var people: PeopleService

    private enum Problem: Equatable {
        case codeInvalid, limit, alreadyHeld, ownBrand, notVerified, failed

        var text: String {
            switch self {
            case .codeInvalid: BipeStrings.brandMarksCodeInvalid
            case .limit: BipeStrings.brandMarksLimit
            case .alreadyHeld: BipeStrings.brandMarksAlreadyHeld
            case .ownBrand: BipeStrings.brandMarksOwnBrand
            case .notVerified: BipeStrings.brandMarksNotVerified
            case .failed: BipeStrings.brandMarksFailed
            }
        }

        init(_ error: BipeAPIError) {
            switch error {
            case .server(.notFound, _, _): self = .codeInvalid
            case .server(.brandMarkLimit, _, _): self = .limit
            case .server(.badRequest, "already_held", _): self = .alreadyHeld
            case .server(.badRequest, "own_brand", _): self = .ownBrand
            case .server(.brandNotVerified, _, _): self = .notVerified
            default: self = .failed
            }
        }
    }

    private struct Offer: Identifiable {
        let code: String
        let preview: BipeBrandMarkInvitePreview
        var id: String { code }
    }

    @State private var held: [BipeHeldBrandMark] = []
    @State private var granted = BipeGrantedBrandMarks(invites: [], marks: [])
    @State private var resellerNames: [String: String] = [:]
    @State private var codeText = ""
    @State private var offer: Offer?
    @State private var label = BipeRegistries.brandMarkLabels.first ?? ""
    @State private var created: BipeCreatedBrandMarkInvite?
    @State private var problem: Problem?
    @State private var working = false
    @State private var removing: BipeHeldBrandMark?
    @State private var ending: BipeGrantedBrandMark?

    /// The name of this account's verified company: only a verified brand grants. `nil` hides the grant controls.
    @State private var brandName: String?

    var body: some View {
        List {
            heldSection
            codeSection
            grantSection
            if !granted.invites.isEmpty {
                openCodesSection
            }
            if !granted.marks.isEmpty {
                grantedSection
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BipeStrings.brandMarksTitle)
        .accessibilityIdentifier(BipeScreen.brandMarks.rawValue)
        .disabled(working)
        .task { await load() }
        .sheet(item: $offer) { offer in
            offerSheet(offer)
        }
        .alert(removing.map { BipeStrings.brandMarksRemoveConfirm($0.brandName) } ?? "",
               isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button(BipeStrings.brandMarksRemove, role: .destructive) {
                if let mark = removing { run { try await api.endBrandMark(id: mark.id) } }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
        .alert(ending.map { BipeStrings.brandMarksEndConfirm(resellerNames[$0.reseller] ?? $0.reseller) } ?? "",
               isPresented: Binding(get: { ending != nil }, set: { if !$0 { ending = nil } })) {
            Button(BipeStrings.brandMarksEnd, role: .destructive) {
                if let mark = ending { run { try await api.endBrandMark(id: mark.id) } }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    // MARK: - Sections

    private var heldSection: some View {
        Section {
            if held.isEmpty {
                Text(BipeStrings.brandMarksHeldEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            ForEach(held) { mark in
                HStack {
                    Text(Self.line(label: mark.label, brandName: mark.brandName))
                        .foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    Button(BipeStrings.brandMarksRemove, role: .destructive) { removing = mark }
                        .buttonStyle(.borderless)
                }
            }
        } header: {
            Text(BipeStrings.brandMarksHeldHeader)
        } footer: {
            Text(BipeStrings.brandMarksHeldFooter)
        }
    }

    private var codeSection: some View {
        Section {
            TextField(BipeStrings.brandMarksCodeField, text: $codeText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(BipeStrings.brandMarksCheck) { check() }
                .disabled(codeText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } footer: {
            if let problem {
                Text(problem.text)
                    .foregroundStyle(BipeColor.trustCautionInk)
            }
        }
    }

    @ViewBuilder
    private var grantSection: some View {
        Section {
            if let brandName {
                Picker(BipeStrings.brandMarkTitle, selection: $label) {
                    ForEach(BipeRegistries.brandMarkLabels, id: \.self) { id in
                        Text(Self.line(label: id, brandName: brandName)).tag(id)
                    }
                }
                Button(BipeStrings.brandMarksMakeCode) {
                    run { created = try await api.createBrandMarkInvite(label: label) }
                }
                if let created {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(created.code)
                            .font(.title3.monospaced())
                            .foregroundStyle(BipeColor.inkPrimary)
                            .textSelection(.enabled)
                        Text(BipeStrings.brandMarksCodeReady(Self.days(created.invite).formatted()))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                        HStack {
                            Button(BipeStrings.appCopy) { UIPasteboard.general.string = created.link }
                                .buttonStyle(.borderless)
                            Spacer()
                            ShareLink(item: created.link) {
                                Text(BipeStrings.appShare)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
            } else {
                Text(BipeStrings.brandMarksNotVerified)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        } header: {
            Text(BipeStrings.brandMarksGrantHeader)
        } footer: {
            Text(BipeStrings.brandMarksGrantFooter)
        }
    }

    private var openCodesSection: some View {
        Section {
            ForEach(granted.invites) { invite in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Self.line(label: invite.label, brandName: brandName ?? ""))
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(invite.expiresAt.formatted(date: .abbreviated, time: .omitted))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    Button(BipeStrings.brandMarksCancelCode, role: .destructive) {
                        run { try await api.cancelBrandMarkInvite(id: invite.id) }
                    }
                    .buttonStyle(.borderless)
                }
            }
        } header: {
            Text(BipeStrings.brandMarksOpenCodes)
        }
    }

    private var grantedSection: some View {
        Section {
            ForEach(granted.marks) { mark in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(resellerNames[mark.reseller] ?? mark.reseller)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(Self.line(label: mark.label, brandName: brandName ?? ""))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    Button(BipeStrings.brandMarksEnd, role: .destructive) { ending = mark }
                        .buttonStyle(.borderless)
                }
            }
        } header: {
            Text(BipeStrings.brandMarksGrantedHeader)
        }
    }

    private func offerSheet(_ offer: Offer) -> some View {
        NavigationStack {
            List {
                Section {
                    Text(Self.line(label: offer.preview.label, brandName: offer.preview.brandName))
                        .font(.headline)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.badgeCompanyCnpj + " " + BipeCNPJ.formatted(offer.preview.cnpj))
                        .foregroundStyle(BipeColor.inkSecondary)
                } header: {
                    Text(BipeStrings.brandMarksOfferTitle(offer.preview.brandName))
                }
                Section {
                    Text(BipeStrings.brandMarksBrandSees)
                    Text(BipeStrings.brandMarksBrandNotSees)
                }
                .foregroundStyle(BipeColor.inkPrimary)
                Section {
                    Button(BipeStrings.brandMarksAccept) {
                        self.offer = nil
                        run {
                            _ = try await api.acceptBrandMark(code: offer.code)
                            codeText = ""
                        }
                    }
                    Button(BipeStrings.brandMarksRefuse, role: .destructive) {
                        self.offer = nil
                        run {
                            try await api.refuseBrandMark(code: offer.code)
                            codeText = ""
                        }
                    }
                }
            }
            .navigationTitle(BipeStrings.brandMarkTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { self.offer = nil }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Actions

    private func load() async {
        do {
            held = try await api.heldBrandMarks()
            granted = try await api.grantedBrandMarks()
            brandName = try await api.ownBrandName()
        } catch {
            problem = .failed
            return
        }
        let ids = granted.marks.map(\.reseller).filter { resellerNames[$0] == nil }
        for person in await people.people(ids) {
            resellerNames[person.userId] = person.displayName
        }
    }

    /// Action check: the code out of the field, then the offer.
    private func check() {
        guard let code = BipeBrandMarkCode.extract(codeText) else {
            problem = .codeInvalid
            return
        }
        run { offer = Offer(code: code, preview: try await api.brandMarkInvite(code: code)) }
    }

    /// Runs one call, shows its problem, then reloads the lists.
    private func run(_ call: @escaping @MainActor () async throws(BipeAPIError) -> Void) {
        Task {
            working = true
            defer { working = false }
            problem = nil
            do {
                try await call()
            } catch {
                problem = Problem(error)
            }
            removing = nil
            ending = nil
            await load()
        }
    }

    // MARK: - Text

    static func line(label: String, brandName: String) -> String {
        switch label {
        case "revendedora": BipeStrings.brandMarkLabelRevendedora(brandName)
        case "revendedor": BipeStrings.brandMarkLabelRevendedor(brandName)
        case "corretor": BipeStrings.brandMarkLabelCorretor(brandName)
        case "representante": BipeStrings.brandMarkLabelRepresentante(brandName)
        default: brandName
        }
    }

    /// The invite's life in whole days, for bipe_brand_marks_code_ready.
    private static func days(_ invite: BipeBrandMarkInvite) -> Int {
        max(1, Int((invite.expiresAt.timeIntervalSince(invite.createdAt) / 86_400).rounded()))
    }
}
