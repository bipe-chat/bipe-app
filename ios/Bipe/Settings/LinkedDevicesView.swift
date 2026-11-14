// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Spec linked-devices (ADR 0291): every device of the account with its kind and details, this phone first, and
/// signing out one device or all others natively through bipe-api after the biometric check.
@MainActor
final class LinkedDevicesModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded([BipeDevices.Row])
        case failed
    }

    /// What the sign-out sheet asks about.
    enum SignOutTarget: Equatable, Identifiable {
        case one(BipeDevices.Row)
        case others

        var id: String {
            switch self {
            case .one(let row): row.deviceId
            case .others: "others"
            }
        }
    }

    enum SignOutPhase: Equatable {
        case idle
        case confirmIdentity
        case loading
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    /// Place per IP (GET /v1/web-link/place). The IP goes to no outside service.
    @Published private(set) var places: [String: String] = [:]
    @Published var signOutTarget: SignOutTarget?
    @Published private(set) var signOutPhase: SignOutPhase = .idle
    @Published var notice: String?
    let model: SettingsModel

    init(model: SettingsModel) {
        self.model = model
    }

    var rows: [BipeDevices.Row] {
        if case .loaded(let rows) = phase { return rows }
        return []
    }

    var devices: [BipeDevices.Row] { rows.filter { $0.section != "programs" } }
    var programs: [BipeDevices.Row] { rows.filter { $0.section == "programs" } }
    /// Action askSignOutOthers is shown when two or more other devices exist.
    var showsSignOutOthers: Bool { rows.filter(\.canSignOut).count >= 2 }

    /// The place of a row, when known.
    func place(of row: BipeDevices.Row) -> String? {
        row.ip.flatMap { places[$0] }
    }

    /// The title of the approving device for device-detail linkedBy.
    func name(of deviceId: String) -> String {
        rows.first { $0.deviceId == deviceId }?.title ?? deviceId
    }

    /// Action appear: the devices, the entries of the drafts room, deviceRows, then the place of each row.
    func load() async {
        guard let list = await DeviceEntries.list(session: model.session) else {
            if rows.isEmpty { phase = .failed }
            return
        }
        phase = .loaded(list.rows)
        model.setDeviceCounts(list.counts)
        await loadPlaces(list.rows)
    }

    /// Action retry.
    func retry() async {
        phase = .loading
        await load()
    }

    private func loadPlaces(_ rows: [BipeDevices.Row]) async {
        let api = model.api
        for ip in Set(rows.compactMap(\.ip)) where places[ip] == nil {
            let found = try? await model.authorized { (token: String) async throws(BipeAPIError) -> BipeWebLinkPlace in
                try await api.webLinkPlace(ip: ip, accessToken: token)
            }
            if let label = found?.label() { places[ip] = label }
        }
    }

    /// Action confirmSignOut and confirmSignOutOthers: the biometric check, then the bipe-api route, then a reload.
    func confirmSignOut() async {
        guard let target = signOutTarget, signOutPhase == .idle else { return }
        signOutPhase = .confirmIdentity
        guard await OwnerCheck.owner(reason: BipeStrings.linkedDevicesSignOutBiometric) else {
            signOutPhase = .idle
            return
        }
        signOutPhase = .loading
        let api = model.api
        do throws(BipeAPIError) {
            switch target {
            case .one(let row):
                try await model.authorized { (token: String) async throws(BipeAPIError) in
                    try await api.signOutDevice(row.deviceId, accessToken: token)
                }
            case .others:
                _ = try await model.authorized { (token: String) async throws(BipeAPIError) -> Int in
                    try await api.signOutOtherDevices(accessToken: token)
                }
            }
        } catch {
            signOutPhase = .failed
            return
        }
        BipeHaptics.play(.success)
        signOutTarget = nil
        signOutPhase = .idle
        notice = BipeStrings.appLinkedDevicesSignedOut
        await load()
    }

    func cancelSignOut() {
        guard signOutPhase == .idle || signOutPhase == .failed else { return }
        signOutTarget = nil
        signOutPhase = .idle
    }
}

/// Spec linked-devices inside the group Aparelhos e assistentes (ADR 0291): the sections Aparelhos and Assistentes e
/// programas of SettingsGroupView. Link a computer is the group's registry row linkComputer.
struct LinkedDevicesSection: View {
    @StateObject private var devices: LinkedDevicesModel
    @ObservedObject private var drafts = AssistantDraftsStore.shared

    init(model: SettingsModel) {
        _devices = StateObject(wrappedValue: LinkedDevicesModel(model: model))
    }

    var body: some View {
        Section {
            content
        } header: {
            Text(BipeStrings.devicesSectionDevices)
        } footer: {
            Text(BipeStrings.linkedDevicesFooter)
        }
        .accessibilityIdentifier(BipeScreen.linkedDevices.rawValue)
        .task { await devices.load() }
        // A device entry that arrives later in the drafts room fills its row.
        .onChange(of: drafts.deviceEntries) { _, _ in Task { await devices.load() } }
        .linkedDevicesSignOut(devices)
        .toast($devices.notice)
        if !devices.programs.isEmpty {
            Section {
                ForEach(devices.programs) { row in
                    DeviceRowLink(row: row, devices: devices)
                }
            } header: {
                Text(BipeStrings.devicesSectionPrograms)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch devices.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.linkedDevicesError)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button {
                    Task { await devices.retry() }
                } label: {
                    Text(BipeStrings.appRetry)
                }
            }
            .frame(maxWidth: .infinity)
        case .loaded:
            if devices.devices.isEmpty {
                Text(BipeStrings.linkedDevicesEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                ForEach(devices.devices) { row in
                    DeviceRowLink(row: row, devices: devices)
                }
                if devices.showsSignOutOthers {
                    Button(role: .destructive) {
                        // Action askSignOutOthers.
                        devices.signOutTarget = .others
                    } label: {
                        Text(BipeStrings.devicesSignOutOthers)
                    }
                }
            }
        }
    }
}

/// One row: opens device-detail; a swipe signs the device out (action askSignOut).
private struct DeviceRowLink: View {
    let row: BipeDevices.Row
    @ObservedObject var devices: LinkedDevicesModel

    var body: some View {
        NavigationLink {
            DeviceDetailView(row: row, devices: devices)
        } label: {
            DeviceRowLabel(row: row, place: devices.place(of: row))
        }
        // The same action as the swipe, for VoiceOver.
        .accessibilityActions {
            if row.canSignOut {
                Button(BipeStrings.linkedDevicesSignOut) { devices.signOutTarget = .one(row) }
            }
        }
        .swipeActions(edge: .trailing) {
            if row.canSignOut {
                Button(role: .destructive) {
                    devices.signOutTarget = .one(row)
                } label: {
                    Text(BipeStrings.linkedDevicesSignOut)
                }
            }
        }
    }
}

/// Icon, name, kind and details, last use and place.
private struct DeviceRowLabel: View {
    let row: BipeDevices.Row
    let place: String?

    var body: some View {
        HStack(spacing: 12) {
            (PhosphorIcon(rawValue: row.icon) ?? .warning).image
                .resizable()
                .frame(width: 22, height: 22)
                .foregroundStyle(row.warning ? BipeColor.trustCautionInk : BipeColor.accentInk)
            VStack(alignment: .leading, spacing: 2) {
                if row.isThis {
                    Text(BipeStrings.devicesThisDevice)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BipeColor.accentInk)
                }
                Text(row.title)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(DeviceText.details(row))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                if let use = DeviceText.use(row, place: place) {
                    Text(use)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                if row.warning {
                    Text(BipeStrings.devicesUnknownWarning)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustCautionInk)
                }
            }
        }
        .contentShape(Rectangle())
    }
}

/// The text lines of a device row, shared by the row and device-detail.
enum DeviceText {
    /// Kind, system and app, and "fora do Brasil" for a program that sends data out of Brazil.
    static func details(_ row: BipeDevices.Row) -> String {
        var parts = [row.kindLabel]
        if let os = row.os { parts.append(os) }
        if let app = row.app, app != row.name { parts.append(app) }
        if row.leavesBrazil { parts.append(BipeStrings.devicesOutsideBrazil) }
        return parts.joined(separator: " · ")
    }

    /// Last use and place. This phone shows no last use.
    static func use(_ row: BipeDevices.Row, place: String?) -> String? {
        var parts: [String] = []
        if !row.isThis, let lastUsed = row.lastUsed {
            parts.append(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
        }
        if let place { parts.append(place) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// Spec device-detail (ADR 0291): everything Bipe knows about one device.
struct DeviceDetailView: View {
    let row: BipeDevices.Row
    @ObservedObject var devices: LinkedDevicesModel
    @ObservedObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss

    init(row: BipeDevices.Row, devices: LinkedDevicesModel) {
        self.row = row
        self.devices = devices
        _settings = ObservedObject(wrappedValue: devices.model)
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    (PhosphorIcon(rawValue: row.icon) ?? .warning).image
                        .resizable()
                        .frame(width: 28, height: 28)
                        .foregroundStyle(row.warning ? BipeColor.trustCautionInk : BipeColor.accentInk)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.title)
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(DeviceText.details(row))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                if row.warning {
                    Text(BipeStrings.devicesUnknownWarning)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.trustCautionInk)
                }
            } footer: {
                if row.isThis {
                    Text(BipeStrings.devicesThisDeviceHint)
                }
            }
            Section {
                if !row.isThis, let lastUsed = row.lastUsed {
                    Text(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
                }
                if let place = devices.place(of: row) {
                    Text(BipeStrings.deviceDetailPlace(place))
                }
                if let ip = row.ip {
                    Text(BipeStrings.deviceDetailIp(ip))
                }
                if let linked = row.linkedDate {
                    Text(BipeStrings.deviceDetailLinkedAt(linked.formatted(date: .abbreviated, time: .shortened)))
                }
                if let linkedBy = row.linkedBy {
                    Text(BipeStrings.deviceDetailLinkedBy(devices.name(of: linkedBy)))
                }
                Text(row.attested ? BipeStrings.deviceDetailAttested : BipeStrings.deviceDetailNotAttested)
                // Action copyId: the one place that shows the device ID (ADR 0291).
                Button {
                    UIPasteboard.general.string = row.deviceId
                    BipeHaptics.play(.tick)
                    devices.notice = BipeStrings.deviceDetailIdCopied
                } label: {
                    Text(BipeStrings.deviceDetailId(row.deviceId))
                        .foregroundStyle(BipeColor.inkPrimary)
                }
            }
            .foregroundStyle(BipeColor.inkPrimary)
            if row.kind == "assistant" {
                Section {
                    // Action toggleAssistantSend: the same value as the row assistantSend of the group.
                    Toggle(isOn: Binding(get: { settings.assistantSend ?? false }, set: { isOn in
                        Task {
                            if await settings.setAssistantSend(isOn) { BipeHaptics.play(.tick) }
                        }
                    })) {
                        SettingsRowLabel(title: BipeStrings.privacyAssistantSend, footer: BipeStrings.privacyAssistantSendFooter)
                    }
                    .disabled(settings.assistantSend == nil)
                }
            }
            if row.canSignOut {
                Section {
                    Button(role: .destructive) {
                        devices.signOutTarget = .one(row)
                    } label: {
                        Text(BipeStrings.linkedDevicesSignOut)
                    }
                }
            }
        }
        .navigationTitle(row.isThis ? BipeStrings.devicesThisDevice : row.title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.deviceDetail.rawValue)
        .linkedDevicesSignOut(devices)
        .toast($devices.notice)
        // The device left the list: its detail closes.
        .onChange(of: devices.rows) { _, rows in
            if case .loaded = devices.phase, !rows.contains(where: { $0.deviceId == row.deviceId }) { dismiss() }
        }
    }
}

private extension View {
    /// The sign-out sheet of linked-devices and device-detail, and its error.
    func linkedDevicesSignOut(_ devices: LinkedDevicesModel) -> some View {
        modifier(SignOutDialogs(devices: devices))
    }
}

private struct SignOutDialogs: ViewModifier {
    @ObservedObject var devices: LinkedDevicesModel

    func body(content: Content) -> some View {
        content
            .confirmationDialog(title,
                                isPresented: Binding(get: { devices.signOutTarget != nil && devices.signOutPhase == .idle },
                                                     set: { if !$0 { devices.cancelSignOut() } }),
                                titleVisibility: .visible) {
                Button(role: .destructive) {
                    Task { await devices.confirmSignOut() }
                } label: {
                    Text(devices.signOutTarget == .others ? BipeStrings.devicesSignOutOthers : BipeStrings.linkedDevicesSignOut)
                }
                Button(role: .cancel) {
                    devices.cancelSignOut()
                } label: {
                    Text(BipeStrings.appCancel)
                }
            } message: {
                Text(devices.signOutTarget == .others ? BipeStrings.devicesSignOutOthersBody : BipeStrings.linkedDevicesSignOutBody)
            }
            .overlay {
                if devices.signOutPhase == .loading {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(BipeStrings.appLinkedDevicesSignOutLoading)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    .padding(24)
                    .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
                }
            }
            .alert(BipeStrings.linkedDevicesTitle, isPresented: Binding(get: { devices.signOutPhase == .failed }, set: { _ in })) {
                Button(role: .cancel) {
                    devices.cancelSignOut()
                } label: {
                    Text(BipeStrings.appClose)
                }
            } message: {
                Text(BipeStrings.appLinkedDevicesSignOutError)
            }
    }

    private var title: String {
        switch devices.signOutTarget {
        case .one(let row): BipeStrings.linkedDevicesSignOutTitle(row.title)
        case .others: BipeStrings.devicesSignOutOthersTitle
        case nil: ""
        }
    }
}
