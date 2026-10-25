// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Spec business-claim (bipe-shared product/screens/business-claim.json). The server decides every step (ADR 0042).
@MainActor
final class BusinessClaimModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case noClaim
        case claim(BipeBusinessClaim)
        case failed(String)
    }

    /// Which part a notice belongs to, so it shows under that part.
    enum Part: Equatable {
        case cnpj
        case proof
        case domain(String?)
    }

    @Published private(set) var phase: Phase = .loading
    @Published var cnpjInput = ""
    @Published var domainInput = ""
    @Published private(set) var busy = false
    @Published private(set) var notice: (part: Part, text: String)?

    private let session: AccountSession
    private let api: BipeAPIClient
    /// Called after a change, so the trust ladder reads the claim again.
    var onChange: () -> Void = {}

    /// The .p7s file of an e-CNPJ signature is a few KB; the API takes 64 KB of base64.
    static let maxSignatureBytes = 48 * 1024

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    var claim: BipeBusinessClaim? {
        if case .claim(let claim) = phase { claim } else { nil }
    }

    var normalizedCnpj: String? { BipeCNPJ.normalized(cnpjInput) }

    func notice(for part: Part) -> String? {
        notice?.part == part ? notice?.text : nil
    }

    /// Action appear: GET /v1/me/business. 404 is no claim.
    func appear() async {
        if claim == nil { phase = .loading }
        do {
            let claim = try await call { (api: BipeAPIClient, token: String) async throws(BipeAPIError) -> BipeBusinessClaim? in
                try await api.myBusiness(accessToken: token)
            }
            phase = claim.map(Phase.claim) ?? .noClaim
        } catch let error as BipeAPIError {
            phase = .failed(error.localizedMessage)
        } catch {
            phase = .failed(BipeStrings.errorGeneric)
        }
    }

    /// Action submitCnpj.
    func submitCnpj() async {
        guard let cnpj = normalizedCnpj, !busy else { return }
        await run(.cnpj) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            self.phase = .claim(try await api.claimBusiness(cnpj: cnpj, accessToken: token))
        }
    }

    /// Action pickSignature: the bytes of the .p7s file the user chose.
    func submitSignature(from url: URL) async {
        guard !busy else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), !data.isEmpty, data.count <= Self.maxSignatureBytes,
              data.first == 0x30 else {
            notice = (.proof, BipeStrings.claimSignatureUnreadable)
            return
        }
        await run(.proof) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            self.phase = .claim(try await api.proveBusinessWithEcnpj(signature: data, accessToken: token))
        }
    }

    /// Action addDomain.
    func addDomain() async {
        guard !busy else { return }
        guard let domain = BipeCNPJ.domain(domainInput) else {
            notice = (.domain(nil), BipeStrings.claimDomainInvalid)
            return
        }
        await run(.domain(nil)) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            _ = try await api.addBusinessDomain(domain, accessToken: token)
            self.domainInput = ""
            if let claim = try await api.myBusiness(accessToken: token) { self.phase = .claim(claim) }
        }
    }

    /// Action checkDomain.
    func checkDomain(_ domain: String) async {
        guard !busy else { return }
        await run(.domain(domain)) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            let checked = try await api.checkBusinessDomain(domain, accessToken: token)
            if checked.verifiedAt == nil { self.notice = (.domain(domain), BipeStrings.claimDomainNotYet) }
            if let claim = try await api.myBusiness(accessToken: token) { self.phase = .claim(claim) }
        }
    }

    /// Action withdraw, after the confirmation.
    func withdraw() async {
        guard !busy else { return }
        await run(.cnpj) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            try await api.withdrawBusiness(accessToken: token)
            self.phase = .noClaim
            self.cnpjInput = ""
        }
    }

    /// Actions copyChallenge and copyTxt.
    func copy(_ value: String, part: Part) {
        UIPasteboard.general.string = value
        notice = (part, BipeStrings.claimCopied)
    }

    private func run(_ part: Part, _ body: (BipeAPIClient, String) async throws(BipeAPIError) -> Void) async {
        busy = true
        notice = nil
        defer { busy = false }
        do {
            try await call(body)
            onChange()
        } catch let error as BipeAPIError {
            notice = (part, error.localizedMessage)
        } catch {
            notice = (part, BipeStrings.errorGeneric)
        }
    }

    private func call<T>(_ body: (BipeAPIClient, String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        let api = api
        return try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> T in
            try await body(api, token)
        }
    }
}

struct BusinessClaimView: View {
    @StateObject private var model: BusinessClaimModel
    private let session: AccountSession
    private let api: BipeAPIClient
    @State private var picking = false
    @State private var confirmingWithdraw = false
    @State private var showsSupport = false

    init(session: AccountSession, api: BipeAPIClient, onChange: @escaping () -> Void) {
        let model = BusinessClaimModel(session: session, api: api)
        model.onChange = onChange
        _model = StateObject(wrappedValue: model)
        self.session = session
        self.api = api
    }

    var body: some View {
        content
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.claimTitle)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier(BipeScreen.businessClaim.rawValue)
            .task { await model.appear() }
            .fileImporter(isPresented: $picking, allowedContentTypes: [UTType(filenameExtension: "p7s") ?? .data, .data]) { result in
                if case .success(let url) = result { Task { await model.submitSignature(from: url) } }
            }
            .confirmationDialog(BipeStrings.claimWithdrawConfirm, isPresented: $confirmingWithdraw, titleVisibility: .visible) {
                Button(BipeStrings.claimWithdraw, role: .destructive) { Task { await model.withdraw() } }
                Button(BipeStrings.appCancel, role: .cancel) {}
            }
            .sheet(isPresented: $showsSupport) {
                NavigationStack { SupportOpenView(session: session, api: api) }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            VStack(spacing: 16) {
                Text(message).foregroundStyle(BipeColor.inkSecondary).multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await model.appear() } }.buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .noClaim:
            List { cnpjForm }.listStyle(.insetGrouped)
        case .claim(let claim):
            List {
                statusSection(claim)
                if claim.status == .awaitingProof, let challenge = claim.challenge {
                    proofSection(challenge)
                }
                if claim.status == .awaitingProof || claim.status == .verified {
                    domainsSection(claim)
                }
                if let names = claim.reservedNames, !names.isEmpty {
                    reservedSection(names)
                }
                Section {
                    Button(role: .destructive) { confirmingWithdraw = true } label: {
                        IconLabel(icon: .trash, text: BipeStrings.claimWithdraw, tint: BipeColor.trustDangerInk)
                    }
                    .disabled(model.busy)
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private var cnpjForm: some View {
        Section {
            TextField(BipeStrings.badgeCompanyCnpj, text: $model.cnpjInput)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .onChange(of: model.cnpjInput) { _, value in
                    // Formats a full CNPJ as the user types; partial input stays as typed.
                    if let cnpj = BipeCNPJ.normalized(value), BipeCNPJ.formatted(cnpj) != value { model.cnpjInput = BipeCNPJ.formatted(cnpj) }
                }
            Button {
                Task { await model.submitCnpj() }
            } label: {
                HStack {
                    Text(BipeStrings.claimSubmit)
                    if model.busy { Spacer(); ProgressView() }
                }
            }
            .disabled(model.normalizedCnpj == nil || model.busy)
            if let notice = model.notice(for: .cnpj) {
                NoticeText(text: notice)
            }
        } header: {
            Text(BipeStrings.claimTitle)
        } footer: {
            Text(BipeStrings.claimIntro)
        }
    }

    private func statusSection(_ claim: BipeBusinessClaim) -> some View {
        Section {
            HStack(alignment: .top, spacing: 10) {
                statusIcon(claim.status)
                Text(statusText(claim)).foregroundStyle(BipeColor.inkPrimary)
            }
            if let cnpj = claim.cnpj {
                fact(BipeStrings.badgeCompanyCnpj, BipeCNPJ.formatted(cnpj))
            }
            if let registry = claim.registry {
                fact(BipeStrings.badgeCompanyLegalName, registry.legalName)
                if let tradeName = registry.tradeName {
                    fact(BipeStrings.badgeCompanyTradeName, tradeName)
                }
                Text(registry.active ? BipeStrings.claimRegistryActive : BipeStrings.claimRegistryInactive)
                    .foregroundStyle(registry.active ? BipeColor.inkPrimary : BipeColor.trustCautionInk)
                if let checked = BipeCNPJ.checkedDate(registry.checkedMonth) {
                    fact(BipeStrings.badgeCompanyChecked, checked.formatted(.dateTime.month(.wide).year()))
                }
            }
            if let notice = model.notice(for: .cnpj) {
                NoticeText(text: notice)
            }
        }
    }

    private func statusText(_ claim: BipeBusinessClaim) -> String {
        switch claim.status {
        case .registryPending: BipeStrings.claimRegistryPending
        case .awaitingProof: BipeStrings.claimAwaitingProof
        case .verified: BipeStrings.claimVerified
        case .rejected: BipeStrings.claimRejected
        case .revoked: claim.reason == "not_active" || claim.reason == "not_found"
            ? BipeStrings.badgeCompanyRevokedNotActive : BipeStrings.badgeCompanyRevoked
        }
    }

    @ViewBuilder
    private func statusIcon(_ status: BipeBusinessClaim.Status) -> some View {
        let (icon, tint): (PhosphorIcon, Color) = switch status {
        case .verified: (.checkCircle, BipeColor.statusSuccess)
        case .registryPending, .awaitingProof: (.clock, BipeColor.inkSecondary)
        case .rejected, .revoked: (.warningCircle, BipeColor.trustCautionInk)
        }
        icon.image.resizable().frame(width: 22, height: 22).foregroundStyle(tint).accessibilityHidden(true)
    }

    private func proofSection(_ challenge: String) -> some View {
        Section {
            Text(BipeStrings.claimProofEcnpj).font(.headline).foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.claimProofEcnpjBody).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
            // The exact text to sign: never trimmed or changed.
            Text(verbatim: challenge)
                .font(.footnote.monospaced())
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
            Button { model.copy(challenge, part: .proof) } label: {
                IconLabel(icon: .copy, text: BipeStrings.claimCopyText)
            }
            ShareLink(item: challenge) {
                IconLabel(icon: .shareNetwork, text: BipeStrings.claimSendText)
            }
            Button { picking = true } label: {
                HStack {
                    IconLabel(icon: .file, text: BipeStrings.claimPickSignature)
                    if model.busy { Spacer(); ProgressView() }
                }
            }
            .disabled(model.busy)
            if let notice = model.notice(for: .proof) {
                NoticeText(text: notice)
            }
        } header: {
            Text(BipeStrings.claimProofTitle)
        }
    }

    private func domainsSection(_ claim: BipeBusinessClaim) -> some View {
        Section {
            ForEach(claim.domains, id: \.domain) { domain in
                VStack(alignment: .leading, spacing: 6) {
                    Text(domain.domain).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                    if domain.verifiedAt != nil {
                        HStack(spacing: 6) {
                            PhosphorIcon.checkCircle.image.resizable().frame(width: 16, height: 16)
                                .foregroundStyle(BipeColor.statusSuccess).accessibilityHidden(true)
                            Text(BipeStrings.claimDomainVerified).font(.subheadline).foregroundStyle(BipeColor.inkPrimary)
                        }
                    } else {
                        if let name = domain.txtName { txtRow(BipeStrings.claimDomainTxtName, name, domain: domain.domain) }
                        if let value = domain.txtValue { txtRow(BipeStrings.claimDomainTxtValue, value, domain: domain.domain) }
                        Button(BipeStrings.claimDomainCheck) { Task { await model.checkDomain(domain.domain) } }
                            .disabled(model.busy)
                        if let notice = model.notice(for: .domain(domain.domain)) {
                            NoticeText(text: notice)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            if claim.domains.count < 5 {
                TextField(BipeStrings.claimDomainPlaceholder, text: $model.domainInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button(BipeStrings.claimDomainAdd) { Task { await model.addDomain() } }
                    .disabled(model.domainInput.isEmpty || model.busy)
                if let notice = model.notice(for: .domain(nil)) {
                    NoticeText(text: notice)
                }
            }
        } header: {
            Text(BipeStrings.claimDomainsTitle)
        } footer: {
            Text(BipeStrings.claimDomainsBody)
        }
    }

    private func txtRow(_ label: String, _ value: String, domain: String) -> some View {
        HStack(alignment: .top) {
            fact(label, value)
            Spacer()
            Button(BipeStrings.claimCopy) { model.copy(value, part: .domain(domain)) }
                .buttonStyle(.borderless)
        }
    }

    private func reservedSection(_ names: [BipeBusinessClaim.ReservedName]) -> some View {
        Section {
            ForEach(names, id: \.name) { name in
                HStack {
                    Text(name.name).foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    Text(name.approved ? BipeStrings.claimReservedApproved : BipeStrings.claimReservedPending)
                        .font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            if names.contains(where: { !$0.approved }) {
                Button { showsSupport = true } label: {
                    IconLabel(icon: .chatText, text: BipeStrings.claimReservedRequest)
                }
            }
        } header: {
            Text(BipeStrings.claimReservedTitle)
        } footer: {
            Text(BipeStrings.claimReservedBody)
        }
    }

    private func fact(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(BipeColor.inkSecondary)
            Text(verbatim: value).foregroundStyle(BipeColor.inkPrimary).textSelection(.enabled)
        }
    }
}

/// An error or a confirmation under the part it belongs to.
private struct NoticeText: View {
    let text: String

    var body: some View {
        Text(text).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
    }
}
