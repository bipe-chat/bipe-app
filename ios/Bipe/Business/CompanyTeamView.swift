// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec company-team: Quem atende (ADR 0251, ADR 0252). One person is a complete state. Only the owner sees devices and
/// actions. Calling a person comes with bipe-chat/bipe#1056.
struct CompanyTeamView: View {
    @ObservedObject var home: WorkHomeModel
    let session: AccountSession
    @StateObject private var settings: SettingsModel
    @State private var accountName = ""
    @State private var linkingOwnPhone = false
    @Environment(\.scenePhase) private var scenePhase

    init(home: WorkHomeModel, session: AccountSession, api: BipeAPIClient) {
        self.home = home
        self.session = session
        _settings = StateObject(wrappedValue: SettingsModel(session: session, api: api))
    }

    private var isOwner: Bool { home.seats?.me.role == "owner" }

    var body: some View {
        List {
            if let list = home.seats {
                people(list)
                if isOwner, !list.unassignedDevices.isEmpty { unassigned(list) }
                if list.activeSeats.count > 1 { shared }
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
                }
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
            if list.activeSeats.count == 1 { Text(BipeStrings.teamAloneBody(accountName)) }
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
