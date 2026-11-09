// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec message-forward: the room's send queue encrypts, shows the local echo and retries after the connection comes back
/// (bipe-chat/bipe#1124). Media keeps its source: nothing is uploaded again.
struct MacForwardSheet: View {
    let message: MacMessage
    let targets: [MacRoomSummary]
    let close: () -> Void
    @State private var picked: Set<String> = []
    @State private var search = ""
    @State private var sending = false
    @State private var failed = false

    private var shown: [MacRoomSummary] {
        targets.filter { !$0.isArchived && (search.isEmpty || $0.name.localizedStandardContains(search)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.forwardTitle).macFont(.headline)
            TextField(BipeStrings.appSearchPlaceholder, text: $search).textFieldStyle(.roundedBorder)
            List(shown, selection: $picked) { row in Text(row.name).lineLimit(1).tag(row.id) }
                .frame(minHeight: 280)
            if failed { MacNotice(text: BipeStrings.errorGeneric, danger: true) }
            HStack {
                Spacer()
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction)
                Button(BipeStrings.forwardAction) { Task { await send() } }
                    .keyboardShortcut(.defaultAction).disabled(picked.isEmpty || sending)
            }
        }.padding(MacDesign.pageInset).frame(width: 460)
    }

    private func send() async {
        guard let json = message.forwardJson, let forwardMessage = message.forwardMessage,
              let extra = BipeForwardMark.extraContentJson(contentJson: json) else { return }
        sending = true
        defer { sending = false }
        var anyFailed = false
        for row in targets where picked.contains(row.id) {
            do {
                let timeline = try await row.room.timeline()
                _ = try await timeline.sendWithExtraContent(msg: contentWithoutRelationFromMessage(message: forwardMessage), extraContentJson: extra)
            }
            catch { anyFailed = true }
        }
        if anyFailed { failed = true } else { close() }
    }
}

/// Spec chat-notifications: the room's mode in this account.
struct MacChatNotificationsSheet: View {
    let row: MacRoomSummary
    let client: Client?
    let close: () -> Void
    @State private var mode: RoomNotificationMode?
    @State private var failed = false

    private var options: [(RoomNotificationMode, String)] {
        row.isDm ? [(.allMessages, BipeStrings.appNotifyAll), (.mute, BipeStrings.appNotifyMute)]
                 : [(.allMessages, BipeStrings.appNotifyAll), (.mentionsAndKeywordsOnly, BipeStrings.appNotifyQuiet), (.mute, BipeStrings.appNotifyMute)]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.settingsNotifications).macFont(.headline)
            Text(row.name).macFont(.callout).foregroundStyle(BipeColor.inkSecondary).lineLimit(1)
            if let mode {
                Picker(BipeStrings.settingsNotifications, selection: Binding(get: { mode }, set: { new in Task { await choose(new) } })) {
                    ForEach(options, id: \.0) { Text($0.1).tag($0.0) }
                }.pickerStyle(.radioGroup).labelsHidden()
            } else { ProgressView() }
            if failed { MacNotice(text: BipeStrings.errorGeneric, danger: true) }
            HStack {
                Spacer()
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.defaultAction)
            }
        }.padding(MacDesign.pageInset).frame(width: 360)
            .task { await load() }
    }

    private func load() async {
        guard let client, let info = try? await row.room.roomInfo() else { return }
        let settings = await client.getNotificationSettings()
        let encrypted = info.encryptionState != .notEncrypted
        if let current = try? await settings.getRoomNotificationSettings(roomId: row.id, isEncrypted: encrypted, isOneToOne: info.isDm) {
            mode = current.mode
        }
    }

    private func choose(_ new: RoomNotificationMode) async {
        guard let client, let old = mode, new != old, let info = try? await row.room.roomInfo() else { return }
        mode = new
        do {
            let settings = await client.getNotificationSettings()
            if old == .mute {
                try await settings.unmuteRoom(roomId: row.id, isEncrypted: info.encryptionState != .notEncrypted, isOneToOne: info.isDm)
            }
            try await settings.setRoomNotificationMode(roomId: row.id, mode: new)
        } catch { mode = old; failed = true }
    }
}
