// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec report-chat: reports a DM or a group to the Bipe team (the homeserver admins), with an optional reason.
/// In the same step the user can block the person (DM), delete the DM, or leave the group.
struct ReportChatView: View {
    enum Phase: Equatable {
        case editing
        case sending
        case sent
        case failed
    }

    let people: PeopleService
    let roomId: String
    let isDm: Bool
    let otherUserId: String?
    /// Shows the toast of a sent report on the screen underneath.
    let onSent: (String) -> Void

    @State private var reason = ""
    @State private var alsoBlock = true
    @State private var alsoDelete = false
    @State private var alsoLeave = false
    @State private var phase: Phase = .editing
    @State private var leaveFailed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(BipeStrings.appReportReasonPlaceholder, text: $reason, axis: .vertical)
                        .lineLimit(3...8)
                } header: {
                    Text(BipeStrings.appReportReason)
                } footer: {
                    Text(isDm ? BipeStrings.dmReportFooter : BipeStrings.appReportGroupFooter)
                }
                Section {
                    if isDm {
                        if otherUserId != nil {
                            Toggle(BipeStrings.appReportAlsoBlock, isOn: $alsoBlock)
                        }
                        Toggle(BipeStrings.appReportAlsoDelete, isOn: $alsoDelete)
                    } else {
                        // A DM never offers a leave (ADR 0141).
                        Toggle(BipeStrings.appReportAlsoLeave, isOn: $alsoLeave)
                    }
                }
                if phase == .failed {
                    Section {
                        Text(BipeStrings.appReportChatError)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
                Section {
                    Button(role: .destructive) {
                        Task { await send() }
                    } label: {
                        HStack {
                            Text(BipeStrings.appReportSendAction)
                            if phase == .sending {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(phase == .sending)
                }
            }
            .tint(BipeColor.accentFill)
            .navigationTitle(isDm ? BipeStrings.dmReport : BipeStrings.appReportGroup)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { dismiss() }
                        .disabled(phase == .sending)
                }
            }
            .alert(BipeStrings.dmReportLeaveFailedTitle, isPresented: $leaveFailed) {
                Button(BipeStrings.appClose) { dismiss() }
            } message: {
                Text(BipeStrings.dmReportLeaveFailedBody)
            }
        }
        .accessibilityIdentifier(BipeScreen.reportChat.rawValue)
        .interactiveDismissDisabled(phase == .sending)
    }

    /// Action send: Room.reportRoom, then in order block, the dm-delete write and leave.
    /// A failed follow-up step does not undo the report.
    private func send() async {
        guard let room = try? people.session.client.getRoom(roomId: roomId) else {
            phase = .failed
            return
        }
        phase = .sending
        do {
            try await room.reportRoom(reason: reason.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            phase = .failed
            return
        }
        phase = .sent
        if isDm {
            if alsoBlock, let otherUserId {
                try? await people.block(otherUserId)
            }
            if alsoDelete {
                try? await people.clearDm(roomId)
            }
        } else if alsoLeave {
            do {
                try await room.leave()
            } catch {
                onSent(BipeStrings.appGroupReported)
                leaveFailed = true
                return
            }
        }
        onSent(isDm ? BipeStrings.dmReported : BipeStrings.appGroupReported)
        dismiss()
    }
}
