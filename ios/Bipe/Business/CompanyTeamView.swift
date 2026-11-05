// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec company-team: Quem atende (ADR 0251, ADR 0252). One person is a complete state. Only the owner sees devices and
/// actions. It stays read only on role attendant or none.
struct CompanyTeamView: View {
    @ObservedObject var home: WorkHomeModel
    let session: AccountSession
    let onLeft: () async -> Void
    @StateObject private var settings: SettingsModel
    @State private var accountName = ""
    @State private var linkingOwnPhone = false
    @State private var adding = false
    @State private var editing: BipeSeat?
    @State private var editedName = ""
    @State private var removing: BipeSeat?
    @Environment(\.scenePhase) private var scenePhase

    init(home: WorkHomeModel, session: AccountSession, api: BipeAPIClient, onLeft: @escaping () async -> Void) {
        self.home = home
        self.session = session
        self.onLeft = onLeft
        _settings = StateObject(wrappedValue: SettingsModel(session: session, api: api))
    }

    private var isOwner: Bool { home.seats?.me.role == "owner" }

    var body: some View {
        List {
            if let list = home.seats {
                people(list)
                if isOwner, !list.unassignedDevices.isEmpty { unassigned(list) }
                if list.activeSeats.count > 1 { shared }
                if list.me.role == "attendant" {
                    Section {
                        CompanyLeaveButton(accountName: accountName, api: home.api, onLeft: onLeft)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BipeStrings.teamTitle)
        .accessibilityIdentifier(BipeScreen.companyTeam.rawValue)
        .task {
            accountName = (try? await session.client.displayName()) ?? ""
            await settings.load()
            await home.loadSeats()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await home.loadSeats() } }
        }
        .fullScreenCover(isPresented: $linkingOwnPhone, onDismiss: { Task { await home.loadSeats() } }) {
            LinkComputerView(settings: settings, mode: .phone)
        }
        .sheet(isPresented: $adding, onDismiss: { Task { await home.loadSeats() } }) {
            CompanyTeamAddView(home: home, settings: settings, accountName: accountName)
        }
        .alert(BipeStrings.teamAddName, isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField(BipeStrings.teamAddName, text: $editedName)
            Button(BipeStrings.teamAddContinue) {
                guard let seat = editing else { return }
                let name = editedName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty, name.count <= 60 else { return }
                Task {
                    await home.seatAction {
                        _ = try await home.api.renameSeat(id: seat.id, name: name)
                        return try await home.api.seats()
                    }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
        .alert(removing.map { BipeStrings.teamRemoveTitle($0.name) } ?? "", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button(BipeStrings.teamRemoveConfirm, role: .destructive) {
                guard let seat = removing else { return }
                Task { await home.seatAction { try await home.api.removeSeat(id: seat.id) } }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(removing.map { BipeStrings.teamRemoveBody($0.name) } ?? "")
        }
    }

    @ViewBuilder
    private func people(_ list: BipeSeatList) -> some View {
        Section {
            ForEach(list.activeSeats) { seat in
                VStack(alignment: .leading, spacing: 2) {
                    Text(list.me.seatId == seat.id ? BipeStrings.teamYou : seat.name).foregroundStyle(BipeColor.inkPrimary)
                    Text(seat.role == "owner" ? BipeStrings.teamRoleOwner : BipeStrings.teamRoleAttendant)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                    if list.me.seatId == seat.id, seat.role != "owner" {
                        Text(BipeStrings.teamYouHere).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                    ForEach(seat.devices) { device in
                        Text(deviceText(device)).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    }
                    // Action openPerson: the owner row has no remove.
                    if isOwner, seat.role != "owner" {
                        HStack(spacing: 16) {
                            Button(BipeStrings.teamAddName) {
                                editedName = seat.name
                                editing = seat
                            }
                            .buttonStyle(.borderless)
                            Button(BipeStrings.teamRemoveConfirm, role: .destructive) { removing = seat }
                                .buttonStyle(.borderless)
                        }
                        .padding(.top, 4)
                    }
                }
            }
            if isOwner {
                Button(BipeStrings.teamAdd) { adding = true }
                    .disabled(list.seats.count >= list.maxPeople)
            }
            if isOwner, let mine = list.me.seatId {
                Button(BipeStrings.teamAddOwn) {
                    Task {
                        _ = try? await home.api.openSeatWindow(seatId: mine)
                        linkingOwnPhone = true
                    }
                }
            }
        } footer: {
            if list.seats.count >= list.maxPeople {
                Text(BipeStrings.teamAddLimit(String(list.maxPeople)))
            } else if list.activeSeats.count == 1 {
                Text(BipeStrings.teamAloneBody(accountName))
            }
        }
    }

    @ViewBuilder
    private func unassigned(_ list: BipeSeatList) -> some View {
        Section {
            ForEach(list.unassignedDevices) { device in
                VStack(alignment: .leading, spacing: 8) {
                    Text(deviceText(device)).foregroundStyle(BipeColor.inkPrimary)
                    Menu(BipeStrings.teamDeviceAssign) {
                        ForEach(list.activeSeats) { seat in
                            Button(seat.name) {
                                Task { await home.seatAction { try await home.api.assignSeatDevice(deviceId: device.deviceId, seatId: seat.id) } }
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                    Button(BipeStrings.teamDeviceRemove, role: .destructive) {
                        Task { await home.seatAction { try await home.api.removeSeatDevice(deviceId: device.deviceId) } }
                    }
                    .buttonStyle(.borderless)
                }
            }
        } header: {
            Text(BipeStrings.teamUnassignedTitle)
        } footer: {
            Text(BipeStrings.teamUnassignedBody)
        }
    }

    private var shared: some View {
        Section(BipeStrings.teamSharedTitle) {
            Text(BipeStrings.teamSharedChats).foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.teamSharedRead).foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.teamSharedChanges).foregroundStyle(BipeColor.inkPrimary)
        }
    }

    private func deviceText(_ device: BipeSeatDevice) -> String {
        device.displayName ?? (device.kind == "web" ? BipeStrings.teamDeviceWeb : BipeStrings.teamDevicePhone)
    }
}

/// Spec company-team leave: confirms, ends every session of this person, and takes the account off this phone. The
/// personal account does not change.
struct CompanyLeaveButton: View {
    let accountName: String
    let api: BipeBusinessAPI
    let onLeft: () async -> Void
    @State private var confirming = false
    @State private var failure: String?

    var body: some View {
        Button(BipeStrings.teamLeave(accountName), role: .destructive) { confirming = true }
            .alert(BipeStrings.teamLeave(accountName), isPresented: $confirming) {
                Button(BipeStrings.teamLeaveConfirm, role: .destructive) {
                    Task {
                        do {
                            _ = try await api.leaveSeat()
                            await onLeft()
                        } catch {
                            failure = WorkHomeModel.seatMessage(error)
                        }
                    }
                }
                Button(BipeStrings.appCancel, role: .cancel) {}
            } message: {
                Text(BipeStrings.teamLeaveBody)
            }
            .toast($failure)
    }
}
