// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec message-forward open: the messages one forward sends, in timeline order.
struct MacForwarding: Identifiable {
    let id = UUID()
    let messages: [MacMessage]

    /// State picked limit: forwardChats, or forwardChatsManyTimes when a message was forwarded many times (bipe-chat/bipe#933).
    var chatLimit: Int { BipeForwardMark.chatLimit(sentCounts: messages.map { BipeForwardMark.count(eventJson: $0.forwardJson) }) }

    /// bipe_forward_limit with the limit, or bipe_forward_limit_many for the many-times limit.
    var limitText: String {
        chatLimit == BipeRegistries.forwardChatsManyTimes && chatLimit < BipeRegistries.forwardChats
            ? BipeStrings.forwardLimitMany : BipeStrings.forwardLimit(String(chatLimit))
    }
}

/// Spec message-forward: the chat picker. The room's send queue encrypts, shows the local echo and retries after the
/// connection comes back (bipe-chat/bipe#1124). Media keeps its source: nothing is uploaded again.
struct MacForwardSheet: View {
    let forwarding: MacForwarding
    let targets: [MacRoomSummary]
    /// Called after the send with the names of the chats that failed; nil when the person cancelled.
    let close: ([String]?) -> Void
    @State private var picked: [String] = []
    @State private var search = ""
    @State private var sending = false
    @State private var showsLimit = false

    private var shown: [MacRoomSummary] {
        targets.filter { !$0.isArchived && (search.isEmpty || $0.name.localizedStandardContains(search)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.forwardTitle).macFont(.headline)
            TextField(BipeStrings.appSearchPlaceholder, text: $search).textFieldStyle(.roundedBorder)
            List(shown) { row in
                let isPicked = picked.contains(row.id)
                Button { toggle(row.id) } label: {
                    HStack {
                        Text(row.name).macFont(.body).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        // The picked chat: a check mark, not only a colour.
                        if isPicked {
                            Image(decorative: "check").renderingMode(.template).resizable()
                                .frame(width: 18, height: 18).foregroundStyle(BipeColor.accentInk)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isPicked ? .isSelected : [])
            }
            .frame(minHeight: 280)
            if showsLimit { MacNotice(text: forwarding.limitText) }
            HStack {
                Spacer()
                if sending { ProgressView() }
                Button(BipeStrings.appCancel) { close(nil) }.keyboardShortcut(.cancelAction).disabled(sending)
                Button(BipeStrings.forwardAction) { Task { close(await send()) } }
                    .keyboardShortcut(.defaultAction).disabled(picked.isEmpty || sending)
            }
        }.padding(MacDesign.pageInset).frame(width: 460)
    }

    /// Action toggle: a chat past the limit shows the limit and is not picked.
    private func toggle(_ roomID: String) {
        if let index = picked.firstIndex(of: roomID) {
            picked.remove(at: index)
            showsLimit = false
        } else if picked.count >= forwarding.chatLimit {
            showsLimit = true
        } else {
            picked.append(roomID)
        }
    }

    /// Action send: each picked chat gets the messages in timeline order. A failed message stops that chat, so the
    /// copies never arrive out of order. Returns the names of the chats that failed.
    private func send() async -> [String] {
        sending = true
        defer { sending = false }
        var failed: [String] = []
        for roomID in picked {
            guard let row = targets.first(where: { $0.id == roomID }) else { continue }
            do {
                let timeline = try await row.room.timeline()
                for message in forwarding.messages {
                    guard let json = message.forwardJson, let content = message.forwardMessage,
                          let extra = BipeForwardMark.extraContentJson(contentJson: json) else { throw CancellationError() }
                    _ = try await timeline.sendWithExtraContent(msg: contentWithoutRelationFromMessage(message: content), extraContentJson: extra)
                }
            } catch {
                failed.append(row.name)
            }
        }
        return failed
    }
}

/// Spec message-forward startSelection: the bar that takes the composer's place while selection mode is on.
struct MacSelectionBar: View {
    @ObservedObject var model: MacTimeline

    var body: some View {
        HStack(spacing: 12) {
            Text(BipeStrings.selectCount(String(model.selection?.count ?? 0))).macFont(.body).fontWeight(.semibold)
            Spacer()
            Button(BipeStrings.appCancel) { model.cancelSelection() }.keyboardShortcut(.cancelAction)
            Button(BipeStrings.appMessageActionDelete, role: .destructive) { model.confirmsDeleteSelection = true }
                .disabled(!model.canDeleteSelection)
            Button(BipeStrings.forwardAction) { model.forwardSelection() }
                .buttonStyle(.bipeProminent).disabled(!model.canForwardSelection)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(BipeColor.surfaceRaised)
        .alert(BipeStrings.selectDeleteTitle, isPresented: $model.confirmsDeleteSelection) {
            Button(BipeStrings.appMessageActionDelete, role: .destructive) { Task { await model.confirmDeleteSelection() } }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.selectDeleteBody)
        }
    }
}

/// Spec chat-timeline forward line (bipe-chat/bipe#933): a forward arrow and bipe_forward_line or bipe_forward_line_many.
struct MacForwardLine: View {
    let source: BipeForwardMark.Source

    var body: some View {
        HStack(spacing: 4) {
            // The reply arrow, mirrored: a forward arrow.
            Image(decorative: "arrow-bend-up-left").renderingMode(.template).resizable()
                .frame(width: 12, height: 12).scaleEffect(x: -1, y: 1)
            Text(source == .forwardedManyTimes ? BipeStrings.forwardLineMany : BipeStrings.forwardLine).macFont(.caption).italic()
        }
        .foregroundStyle(BipeColor.inkSecondary)
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
/// Spec message-forward toggleSelection: while selection mode is on, a click on a selectable row picks it and rows take
/// no other input. A picked row shows a check mark, not only a colour.
struct MacSelectableRow: ViewModifier {
    let isOn: Bool
    let isPicked: Bool
    let canSelect: Bool
    let toggle: () -> Void

    func body(content: Content) -> some View {
        if isOn {
            HStack(alignment: .center, spacing: 10) {
                Group {
                    if isPicked {
                        Image(decorative: "check-circle").renderingMode(.template).resizable().foregroundStyle(BipeColor.accentInk)
                    } else {
                        Circle().strokeBorder(BipeColor.glyphSecondary, lineWidth: 1.5)
                    }
                }
                .frame(width: 22, height: 22)
                .opacity(canSelect ? 1 : 0)
                content.allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .onTapGesture { if canSelect { toggle() } }
            .accessibilityAddTraits(isPicked ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction { if canSelect { toggle() } }
        } else {
            content
        }
    }
}
