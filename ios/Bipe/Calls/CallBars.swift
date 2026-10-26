// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec call start: the voice and video buttons of the chat header.
struct CallHeaderButtons: View {
    @ObservedObject var model: ChatTimelineModel
    let session: AccountSession
    @ObservedObject private var calls = CallCenter.shared
    @State private var focus: String?

    var body: some View {
        HStack(spacing: 4) {
            if model.isEncrypted, focus != nil {
                button(video: false)
                button(video: true)
            }
        }
        .task(id: session.userId) { focus = await calls.focusUrl(for: session) }
    }

    private func button(video: Bool) -> some View {
        Button {
            guard let room = model.matrixRoom else { return }
            Task { await calls.start(session: session, room: room, roomName: model.roomName, video: video) }
        } label: {
            Label {
                Text(video ? BipeStrings.callVideo : BipeStrings.callVoice)
            } icon: {
                (video ? PhosphorIcon.videoCamera : PhosphorIcon.phone).image.resizable().frame(width: 22, height: 22)
            }
        }
        .labelStyle(.iconOnly)
    }
}

/// Spec call join: the bar above the timeline while a call runs in the room.
struct OngoingCallBar: View {
    @ObservedObject var model: ChatTimelineModel
    let session: AccountSession
    @ObservedObject private var calls = CallCenter.shared

    var body: some View {
        let inThisCall = calls.isInCall(roomId: model.roomId)
        if model.isEncrypted, model.hasCall || inThisCall {
            HStack(spacing: 8) {
                PhosphorIcon.phone.image.resizable().frame(width: 18, height: 18)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.callOngoing)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                Button(inThisCall ? BipeStrings.callReturn : BipeStrings.callJoin) {
                    if inThisCall {
                        calls.isPresented = true
                    } else if let room = model.matrixRoom {
                        Task { await calls.start(session: session, room: room, roomName: model.roomName, video: false) }
                    }
                }
                .buttonStyle(.bipeProminent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(BipeColor.accentWash)
        }
    }
}

/// The call screen over the whole app (spec call).
struct CallScreenCover: ViewModifier {
    @ObservedObject private var calls = CallCenter.shared

    func body(content: Content) -> some View {
        content.fullScreenCover(isPresented: $calls.isPresented) {
            if let call = calls.active {
                CallView(call: call, media: calls.media, minimise: { calls.isPresented = false }, close: { calls.dismissFailed() })
                    .bipeRoundedType()
            }
        }
    }
}
