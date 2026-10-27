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
            let ring = CallRingTarget(isDm: model.isDm, peerUserId: model.peerUserId)
            Task { await calls.start(session: session, room: room, roomName: model.roomName, video: video, ring: ring) }
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

/// Spec incoming-call: the ringing screen over the whole app.
struct IncomingCallCover: ViewModifier {
    let session: AccountSession
    @ObservedObject private var incoming = IncomingCalls.shared

    func body(content: Content) -> some View {
        content
            .task(id: session.userId) { await incoming.watch(session) }
            .fullScreenCover(item: Binding(get: { incoming.ringing }, set: { if $0 == nil { incoming.decline() } })) { call in
                IncomingCallView(call: call, media: MediaLoader(client: session.client),
                                 answer: { incoming.answer() }, decline: { incoming.decline() })
                    .bipeRoundedType()
            }
    }
}

/// The ringing screen: who calls, voice or video, answer and decline.
struct IncomingCallView: View {
    let call: IncomingCall
    let media: MediaLoader
    let answer: () -> Void
    let decline: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            AvatarView(name: call.callerName, mxcUrl: call.callerAvatarUrl, loader: media, size: 120)
            Text(call.video ? BipeStrings.callIncomingVideo(call.callerName) : BipeStrings.callIncomingVoice(call.callerName))
                .font(.title2.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Spacer()
            HStack(spacing: 64) {
                roundAction(icon: .phoneX, label: BipeStrings.callDecline, fill: BipeColor.trustDangerInk, action: decline)
                roundAction(icon: call.video ? .videoCamera : .phone, label: BipeStrings.callAnswer, fill: BipeColor.statusSuccess, action: answer)
            }
            .padding(.bottom, 48)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceSunken.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.incomingCall.rawValue)
    }

    private func roundAction(icon: PhosphorIcon, label: String, fill: Color, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                icon.image.resizable().frame(width: 30, height: 30)
                    .foregroundStyle(BipeColor.inkOnAccent)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(fill))
                    .bipeTapTarget()
            }
            .accessibilityLabel(label)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .accessibilityHidden(true)
        }
    }
}
