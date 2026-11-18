// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec report-chat on the Mac: reports a DM or a group to the Bipe team (the homeserver admins), with an optional
/// reason. In the same step: block the person and delete the DM (dm-delete), or leave the group.
struct MacReportChatSheet: View {
    enum Phase: Equatable { case editing, sending, failed }

    let room: Room
    let client: Client
    let isDm: Bool
    /// DM only: the other person, for block.
    let otherUserID: String?
    @ObservedObject var blocked: MacBlockedUsers
    /// The toast text of a sent report.
    let sent: (String) -> Void
    /// dm-delete ran: the room and its marker.
    let cleared: (String, UInt64) -> Void
    let close: () -> Void

    @State private var reason = ""
    @State private var alsoBlock = true
    @State private var alsoDelete = false
    @State private var alsoLeave = false
    @State private var phase: Phase = .editing
    @State private var leaveFailed = false

    var body: some View {
        Form {
            Section {
                TextField(BipeStrings.appReportReasonPlaceholder, text: $reason, axis: .vertical).lineLimit(3...8)
            } header: {
                Text(BipeStrings.appReportReason)
            } footer: {
                Text(isDm ? BipeStrings.dmReportFooter : BipeStrings.appReportGroupFooter)
                    .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
            }
            Section {
                if isDm {
                    if otherUserID != nil { Toggle(BipeStrings.appReportAlsoBlock, isOn: $alsoBlock) }
                    Toggle(BipeStrings.appReportAlsoDelete, isOn: $alsoDelete)
                } else {
                    // A DM never offers a leave (ADR 0141).
                    Toggle(BipeStrings.appReportAlsoLeave, isOn: $alsoLeave)
                }
            }
            if phase == .failed { MacNotice(text: BipeStrings.appReportChatError, danger: true) }
            HStack {
                Spacer()
                if phase == .sending { ProgressView() }
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction).disabled(phase == .sending)
                Button(role: .destructive) { Task { await send() } } label: { Text(BipeStrings.appReportSendAction) }
                    .keyboardShortcut(.defaultAction).disabled(phase == .sending)
            }
        }
        .formStyle(.grouped).frame(width: 460)
        .navigationTitle(isDm ? BipeStrings.dmReport : BipeStrings.appReportGroup)
        .interactiveDismissDisabled(phase == .sending)
        .accessibilityIdentifier(BipeScreen.reportChat.rawValue)
        .alert(BipeStrings.dmReportLeaveFailedTitle, isPresented: $leaveFailed) {
            Button(BipeStrings.appClose, action: close)
        } message: {
            Text(BipeStrings.dmReportLeaveFailedBody)
        }
    }

    /// Action send: Room.reportRoom, then in order block, the dm-delete write and leave. A failed follow-up step does
    /// not undo the report.
    private func send() async {
        phase = .sending
        do {
            try await room.reportRoom(reason: reason.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            phase = .failed
            return
        }
        if isDm {
            if alsoBlock, let otherUserID { try? await blocked.block(otherUserID) }
            if alsoDelete, let marker = try? await DmClear.clear(client: client, room: room) { cleared(room.id(), marker) }
        } else if alsoLeave {
            do {
                try await room.leave()
            } catch {
                sent(BipeStrings.appGroupReported)
                leaveFailed = true
                return
            }
        }
        sent(isDm ? BipeStrings.dmReported : BipeStrings.appGroupReported)
        close()
    }
}
