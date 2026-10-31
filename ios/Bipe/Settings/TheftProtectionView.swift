// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec theft-protection (ADR 0044, ADR 0051, bipe-chat/bipe#600): the owner picks 1 to 3 guardians from verified
/// contacts. A guardian sees the accounts they protect and marks one as stolen, or clears the mark.
@MainActor
final class TheftProtectionModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case shown
        case saving
        case failed
    }

    /// Type GuardianRow.
    struct GuardianRow: Identifiable, Equatable {
        let userId: String
        let name: String

        var id: String { userId }
    }

    /// Type GuardedRow.
    struct GuardedRow: Identifiable, Equatable {
        let userId: String
        let name: String
        let markedAt: Date?

        var id: String { userId }
    }

    /// State confirm, with the account it is about.
    enum Confirm: Equatable {
        case mark(GuardedRow)
        case clear(GuardedRow)
    }

    @Published private(set) var guardians: [GuardianRow] = []
    @Published private(set) var guarding: [GuardedRow] = []
    @Published private(set) var lastMark: String?
    /// Set while the picker sheet is open.
    @Published var picker: [GuardianRow]?
    @Published var confirm: Confirm?
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var error: String?

    private let settings: SettingsModel
    private var names: [String: String] = [:]

    init(settings: SettingsModel) {
        self.settings = settings
    }

    var isFull: Bool { guardians.count >= BipeRegistries.theftMaxGuardians }
    var isBusy: Bool { phase == .loading || phase == .saving }

    /// Action appear: GET /v1/me/guardians. Runs again when the app comes to the foreground.
    func appear() async {
        let api = settings.api
        do {
            let state = try await settings.authorized { (token: String) async throws(BipeAPIError) -> BipeGuardianState in
                try await api.guardians(accessToken: token)
            }
            await show(state)
            phase = .shown
            error = nil
        } catch {
            fail(error)
        }
    }

    /// Action openPicker: verified contacts (UserIdentity.isVerified) that are not guardians.
    func openPicker() async {
        guard !isFull else { return }
        let session = settings.session
        let taken = Set(guardians.map(\.userId))
        let contacts = await GroupPeople.contacts(client: session.client, ownUserId: session.userId)
        var rows: [GuardianRow] = []
        let encryption = session.client.encryption()
        for person in contacts where !taken.contains(person.userId) {
            guard let identity = try? await encryption.userIdentity(userId: person.userId, fallbackToServer: false),
                  identity.isVerified() else { continue }
            names[person.userId] = person.displayName
            rows.append(GuardianRow(userId: person.userId, name: person.displayName))
        }
        picker = rows
    }

    /// Action addGuardian: PUT /v1/me/guardians with the list plus the contact. Closes the picker.
    func addGuardian(_ userId: String) async {
        picker = nil
        guard !isFull, !guardians.contains(where: { $0.userId == userId }) else { return }
        await save(guardians.map(\.userId) + [userId])
    }

    /// Action removeGuardian: PUT /v1/me/guardians without the guardian.
    func removeGuardian(_ userId: String) async {
        await save(guardians.map(\.userId).filter { $0 != userId })
    }

    /// Action askMark or askClear.
    func ask(_ row: GuardedRow) {
        confirm = row.markedAt == nil ? .mark(row) : .clear(row)
    }

    /// Action mark: the owner check of the phone, then POST /v1/theft/{userId}/mark, then appear.
    func mark(_ userId: String) async {
        await runGuardianAction(reason: BipeStrings.theftMarkAction) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            _ = try await api.markStolen(userID: userId, accessToken: token)
        }
    }

    /// Action clear: the owner check, then POST /v1/theft/{userId}/clear, then appear.
    func clear(_ userId: String) async {
        await runGuardianAction(reason: BipeStrings.theftClearAction) { (api: BipeAPIClient, token: String) async throws(BipeAPIError) in
            try await api.clearTheftMark(userID: userId, accessToken: token)
        }
    }

    // MARK: - Private

    private func runGuardianAction(reason: String,
                                   _ request: @escaping (BipeAPIClient, String) async throws(BipeAPIError) -> Void) async {
        confirm = nil
        // Security rule 14: biometrics or the passcode. A phone without a passcode cannot pass (fail closed).
        guard await OwnerCheck.owner(reason: reason) else {
            if !OwnerCheck.canCheckOwner { error = BipeStrings.theftError }
            return
        }
        phase = .saving
        let api = settings.api
        do {
            try await settings.authorized { (token: String) async throws(BipeAPIError) in
                try await request(api, token)
            }
        } catch {
            fail(error)
            return
        }
        await appear()
    }

    private func save(_ userIds: [String]) async {
        phase = .saving
        let api = settings.api
        do {
            let state = try await settings.authorized { (token: String) async throws(BipeAPIError) -> BipeGuardianState in
                try await api.setGuardians(userIds, accessToken: token)
            }
            await show(state)
            phase = .shown
            error = nil
        } catch {
            fail(error)
        }
    }

    private func fail(_ apiError: BipeAPIError) {
        phase = guardians.isEmpty && guarding.isEmpty ? .failed : .shown
        error = apiError == .network ? BipeStrings.errorNetwork : BipeStrings.theftError
    }

    private func show(_ state: BipeGuardianState) async {
        var ids = state.guardians.map(\.userId) + state.guarding.map(\.userId)
        if let lastMark = state.lastMark { ids.append(lastMark.markedBy) }
        await loadNames(ids)
        guardians = state.guardians.map { GuardianRow(userId: $0.userId, name: name($0.userId)) }
        guarding = state.guarding.map { GuardedRow(userId: $0.userId, name: name($0.userId), markedAt: $0.mark?.stolenAt) }
        lastMark = state.lastMark.map {
            BipeStrings.theftLastMark(name($0.markedBy), $0.stolenAt.formatted(date: .abbreviated, time: .shortened))
        }
    }

    /// The display name the app knows, else the @ (POST /v1/handles/resolve), else the localpart. Never the Matrix ID.
    private func loadNames(_ userIds: [String]) async {
        let missing = Array(Set(userIds).filter { names[$0] == nil })
        guard !missing.isEmpty else { return }
        let session = settings.session
        let api = settings.api
        let handles = (try? await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> [String: String] in
            try await api.resolveHandles(userIDs: missing, openID: token)
        }) ?? [:]
        for userId in missing {
            let profile = try? await session.client.getProfile(userId: userId)
            names[userId] = PeopleService.displayName(contactName: nil, profileName: profile?.displayName,
                                                      handle: handles[userId], userId: userId)
        }
    }

    private func name(_ userId: String) -> String {
        names[userId] ?? Person.localpart(of: userId)
    }
}

/// Spec theft-protection.
struct TheftProtectionView: View {
    @StateObject private var model: TheftProtectionModel
    @Environment(\.scenePhase) private var scenePhase

    init(settings: SettingsModel) {
        _model = StateObject(wrappedValue: TheftProtectionModel(settings: settings))
    }

    var body: some View {
        List {
            OsTheftProtectionSection()
            Section {
                Text(BipeStrings.theftIntro)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                if let lastMark = model.lastMark {
                    Text(lastMark)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
            guardiansSection
            guardingSection
            if model.isBusy {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.theftTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.theftProtection.rawValue)
        .task { await model.appear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.appear() } }
        }
        .sheet(isPresented: Binding(get: { model.picker != nil }, set: { if !$0 { model.picker = nil } })) {
            pickerSheet
        }
        .alert(confirmTitle,
               isPresented: Binding(get: { model.confirm != nil }, set: { if !$0 { model.confirm = nil } }),
               presenting: model.confirm) { confirm in
            switch confirm {
            case .mark(let row):
                Button(BipeStrings.theftMarkAction, role: .destructive) {
                    Task { await model.mark(row.userId) }
                }
            case .clear(let row):
                Button(BipeStrings.theftClearAction) {
                    Task { await model.clear(row.userId) }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) { }
        } message: { confirm in
            switch confirm {
            case .mark(let row): Text(BipeStrings.theftMarkConfirmBody(row.name))
            case .clear(let row): Text(BipeStrings.theftClearConfirmBody(row.name))
            }
        }
    }

    private var confirmTitle: String {
        switch model.confirm {
        case .mark(let row): BipeStrings.theftMarkConfirmTitle(row.name)
        case .clear(let row): BipeStrings.theftClearConfirmTitle(row.name)
        case nil: ""
        }
    }

    private var guardiansSection: some View {
        Section {
            ForEach(model.guardians) { guardian in
                Text(guardian.name)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .swipeActions {
                        Button(BipeStrings.theftRemoveGuardian, role: .destructive) {
                            Task { await model.removeGuardian(guardian.userId) }
                        }
                    }
                    .accessibilityAction(named: BipeStrings.theftRemoveGuardian) {
                        Task { await model.removeGuardian(guardian.userId) }
                    }
                    .contextMenu {
                        Button(BipeStrings.theftRemoveGuardian, role: .destructive) {
                            Task { await model.removeGuardian(guardian.userId) }
                        }
                    }
            }
            if model.isFull {
                Text(BipeStrings.theftGuardiansFull)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                Button(BipeStrings.theftAddGuardian) {
                    Task { await model.openPicker() }
                }
                .foregroundStyle(BipeColor.accentInk)
                .disabled(model.isBusy)
            }
        } header: {
            Text(BipeStrings.theftGuardiansSection)
        } footer: {
            Text(BipeStrings.theftGuardiansFooter)
        }
    }

    private var guardingSection: some View {
        Section {
            if model.guarding.isEmpty {
                Text(BipeStrings.theftGuardingEmpty)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            ForEach(model.guarding) { row in
                VStack(alignment: .leading, spacing: 6) {
                    Text(row.name)
                        .foregroundStyle(BipeColor.inkPrimary)
                    if let markedAt = row.markedAt {
                        Text(BipeStrings.theftMarkedAt(markedAt.formatted(date: .abbreviated, time: .shortened)))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                    Button(row.markedAt == nil ? BipeStrings.theftMarkAction : BipeStrings.theftClearAction) {
                        model.ask(row)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(row.markedAt == nil ? BipeColor.trustDangerInk : BipeColor.accentInk)
                    .buttonStyle(.borderless)
                    .disabled(model.isBusy)
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text(BipeStrings.theftGuardingSection)
        }
    }

    private var pickerSheet: some View {
        NavigationStack {
            List {
                if let rows = model.picker, rows.isEmpty {
                    Text(BipeStrings.theftPickerEmpty)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(model.picker ?? []) { row in
                    Button {
                        Task { await model.addGuardian(row.userId) }
                    } label: {
                        Text(row.name).foregroundStyle(BipeColor.inkPrimary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.theftPickerTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { model.picker = nil }
                }
            }
        }
    }
}
