// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Spec linked-devices on the Mac (ADR 0291, readOnly): every device of the account with its kind and details, this
/// Mac first as Este aparelho. No sign-out and no link actions: only a phone does those.
struct MacDeviceSections: View {
    @ObservedObject var model: MacSettingsModel

    private var devices: [BipeDevices.Row] { model.deviceRows.filter { $0.section != "programs" } }
    private var programs: [BipeDevices.Row] { model.deviceRows.filter { $0.section == "programs" } }

    var body: some View {
        Section {
            switch model.devices {
            case .loading:
                ProgressView().frame(maxWidth: .infinity)
            case .failed:
                Text(BipeStrings.linkedDevicesError).foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.appRetry) { Task { await model.retryDevices() } }.buttonStyle(.bipeBordered)
            case .loaded:
                if devices.isEmpty {
                    Text(BipeStrings.linkedDevicesEmpty).foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(devices) { MacDeviceRowLink(row: $0, model: model) }
            }
        } header: {
            Text(BipeStrings.devicesSectionDevices)
        } footer: {
            Text(BipeStrings.macLinkedDevicesFooter)
        }
        if !programs.isEmpty {
            Section {
                ForEach(programs) { MacDeviceRowLink(row: $0, model: model) }
            } header: {
                Text(BipeStrings.devicesSectionPrograms)
            }
        }
    }
}

/// One row: opens device-detail.
private struct MacDeviceRowLink: View {
    let row: BipeDevices.Row
    @ObservedObject var model: MacSettingsModel

    var body: some View {
        NavigationLink {
            MacDeviceDetailView(row: row, model: model)
        } label: {
            HStack(spacing: 12) {
                MacDeviceIcon(row: row, size: 22)
                VStack(alignment: .leading, spacing: 2) {
                    if row.isThis {
                        Text(BipeStrings.devicesThisDevice)
                            .macFont(.caption).fontWeight(.semibold).foregroundStyle(BipeColor.accentInk)
                    }
                    Text(row.title)
                    Text(MacDeviceText.details(row)).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    if let use = MacDeviceText.use(row, place: model.place(of: row)) {
                        Text(use).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                    if row.warning {
                        Text(BipeStrings.devicesUnknownWarning).macFont(.footnote).foregroundStyle(BipeColor.trustCautionInk)
                    }
                }
            }
        }
    }
}

private struct MacDeviceIcon: View {
    let row: BipeDevices.Row
    let size: CGFloat

    var body: some View {
        Image(decorative: row.icon).renderingMode(.template).resizable()
            .frame(width: size, height: size)
            .foregroundStyle(row.warning ? BipeColor.trustCautionInk : BipeColor.accentInk)
            .accessibilityHidden(true)
    }
}

/// The text lines of a device row, as on the phones.
enum MacDeviceText {
    /// Kind, system and app, and "fora do Brasil" for a program that sends data out of Brazil.
    static func details(_ row: BipeDevices.Row) -> String {
        var parts = [row.kindLabel]
        if let os = row.os { parts.append(os) }
        if let app = row.app, app != row.name { parts.append(app) }
        if row.leavesBrazil { parts.append(BipeStrings.devicesOutsideBrazil) }
        return parts.joined(separator: " · ")
    }

    /// Last use and place. This Mac shows no last use.
    static func use(_ row: BipeDevices.Row, place: String?) -> String? {
        var parts: [String] = []
        if !row.isThis, let lastUsed = row.lastUsed {
            parts.append(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
        }
        if let place { parts.append(place) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// Spec device-detail on the Mac: everything Bipe knows about one device, read-only.
private struct MacDeviceDetailView: View {
    let row: BipeDevices.Row
    @ObservedObject var model: MacSettingsModel
    @State private var copied = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    MacDeviceIcon(row: row, size: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.title).macFont(.headline)
                        Text(MacDeviceText.details(row)).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                if row.warning {
                    Text(BipeStrings.devicesUnknownWarning).macFont(.subheadline).foregroundStyle(BipeColor.trustCautionInk)
                }
            } footer: {
                if row.isThis { Text(BipeStrings.devicesThisDeviceHint) }
            }
            Section {
                if !row.isThis, let lastUsed = row.lastUsed {
                    Text(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
                }
                if let place = model.place(of: row) {
                    Text(BipeStrings.deviceDetailPlace(place))
                }
                if let ip = row.ip {
                    Text(BipeStrings.deviceDetailIp(ip))
                }
                if let linked = row.linkedDate {
                    Text(BipeStrings.deviceDetailLinkedAt(linked.formatted(date: .abbreviated, time: .shortened)))
                }
                if let linkedBy = row.linkedBy {
                    Text(BipeStrings.deviceDetailLinkedBy(model.deviceName(of: linkedBy)))
                }
                Text(row.attested ? BipeStrings.deviceDetailAttested : BipeStrings.deviceDetailNotAttested)
                // Action copyId: the one place that shows the device ID (ADR 0291).
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(row.deviceId, forType: .string)
                    copied = true
                } label: {
                    Text(BipeStrings.deviceDetailId(row.deviceId)).foregroundStyle(BipeColor.inkPrimary)
                }.buttonStyle(.plain)
                if copied {
                    Text(BipeStrings.deviceDetailIdCopied).foregroundStyle(BipeColor.inkSecondary)
                }
            }
        }
        .formStyle(.grouped)
        .macFont(.body)
        .navigationTitle(row.isThis ? BipeStrings.devicesThisDevice : row.title)
        .accessibilityIdentifier(BipeScreen.deviceDetail.rawValue)
    }
}
