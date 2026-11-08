// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec work-chat-bar: the line at the top of a customer chat of a work account with two or more people. Who has the
/// chat, its status, Pegar, Passar para, Devolver and Notas. The caller shows it only then; customers never see it.
struct WorkChatBar: View {
    @ObservedObject var queue: WorkQueueStore
    let roomId: String
    let openNotes: () -> Void
    @State private var giving = false

    var body: some View {
        let assignee = queue.assignee(roomId: roomId)
        let busy = queue.updating.contains(roomId)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(queue.barLine(roomId: roomId))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                    Menu {
                        ForEach(BipeChatAssignmentStatus.allCases, id: \.self) { status in
                            Button(Self.title(of: status)) { Task { await queue.setStatus(roomId: roomId, status) } }
                        }
                    } label: {
                        Text(Self.title(of: queue.status(roomId: roomId)))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.accentInk)
                    }
                    .disabled(busy)
                }
                Spacer(minLength: 8)
                if busy { ProgressView() }
                if assignee != queue.mySeatId || assignee == nil {
                    Button(BipeStrings.chatTake) { Task { await queue.take(roomId: roomId) } }
                        .font(.subheadline.weight(.semibold))
                        .disabled(busy || queue.mySeatId == nil)
                }
                Button(BipeStrings.chatNotes, action: openNotes)
                    .font(.subheadline)
                Menu {
                    if queue.activeSeats.contains(where: { $0.id != assignee }) {
                        Button(BipeStrings.chatGive) { giving = true }
                    }
                    if assignee != nil {
                        Button(BipeStrings.chatRelease) { Task { await queue.release(roomId: roomId) } }
                    }
                } label: {
                    PhosphorIcon.dotsThree.image
                        .resizable()
                        .frame(width: 20, height: 20)
                        .foregroundStyle(BipeColor.glyphSecondary)
                        .bipeTapTarget()
                }
                .accessibilityLabel(BipeStrings.chatActions)
                .disabled(busy)
            }
            if queue.isTakenByOther(roomId: roomId) || queue.taken.contains(roomId),
               let name = queue.seatName(queue.assignee(roomId: roomId)) {
                Text(BipeStrings.chatTakenBanner(name))
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            if queue.failed.contains(roomId) {
                Text(BipeStrings.chatAssignError)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BipeColor.surfaceRaised)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(BipeScreen.workChatBar.rawValue)
        .confirmationDialog(BipeStrings.chatGiveTitle, isPresented: $giving, titleVisibility: .visible) {
            ForEach(queue.activeSeats.filter { $0.id != assignee }) { seat in
                Button(seat.name) { Task { await queue.give(roomId: roomId, to: seat.id) } }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    static func title(of status: BipeChatAssignmentStatus) -> String {
        switch status {
        case .open: BipeStrings.chatStatusOpen
        case .waiting: BipeStrings.chatStatusWaiting
        case .solved: BipeStrings.chatStatusSolved
        }
    }
}
