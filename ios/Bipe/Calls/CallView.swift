// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVKit
import BipeKit
import LiveKit
import Phosphor
import SwiftUI

/// Spec call: the full-screen call with the video tiles, the own preview, the controls and the time.
struct CallView: View {
    @ObservedObject var call: CallSession
    let media: MediaLoader?
    let minimise: () -> Void
    let close: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        ZStack {
            BipeColor.surfaceSunken.ignoresSafeArea()
            VStack(spacing: 16) {
                header
                if call.microphoneDenied {
                    denied(BipeStrings.callMicrophoneDenied)
                } else if call.phase == .failed {
                    failed
                } else {
                    tileGrid
                    if !call.extraMembers.isEmpty { memberList }
                    if call.cameraDenied { denied(BipeStrings.callCameraDenied) }
                }
                Spacer(minLength: 0)
                if !call.microphoneDenied, call.phase != .failed { controls }
            }
            .padding(16)
            if call.cameraOn, let track = call.localTrack {
                SwiftUIVideoView(track, layoutMode: .fill, mirrorMode: call.frontCamera ? .mirror : .off)
                    .frame(width: 110, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.trailing, 16)
                    .padding(.bottom, 120)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityIdentifier(BipeScreen.call.rawValue)
    }

    private var header: some View {
        HStack(alignment: .top) {
            Button(action: minimise) {
                PhosphorIcon.caretLeft.image.resizable().frame(width: 22, height: 22).bipeTapTarget()
            }
            .accessibilityLabel(BipeStrings.callMinimise)
            VStack(spacing: 4) {
                Text(call.roomName)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                status
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            .frame(maxWidth: .infinity)
            Color.clear.frame(width: BipeTapTarget.minimum, height: BipeTapTarget.minimum)
        }
        .foregroundStyle(BipeColor.glyphPrimary)
    }

    @ViewBuilder
    private var status: some View {
        switch call.phase {
        case .connecting: Text(BipeStrings.callStateConnecting)
        case .calling: Text(BipeStrings.callStateCalling)
        case .reconnecting: Text(BipeStrings.callStateReconnecting)
        case .declined: Text(BipeStrings.callDeclined)
        case .noAnswer: Text(BipeStrings.callNoAnswer)
        case .ended: Text(BipeStrings.callStateEnded)
        case .failed: Text(BipeStrings.callErrorConnect)
        case .connected:
            if let start = call.connectedAt {
                TimelineView(.periodic(from: start, by: 1)) { context in
                    Text(Duration.seconds(max(0, context.date.timeIntervalSince(start))).formatted(.time(pattern: .minuteSecond)))
                        .monospacedDigit()
                }
            }
        }
    }

    private var tileGrid: some View {
        let shown = Array(call.tiles.prefix(CallSession.maxTiles))
        let columns = shown.count <= 1 ? 1 : 2
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) {
            ForEach(shown) { tile in
                CallTileView(tile: tile, media: media, tall: shown.count <= 2)
            }
        }
    }

    private var memberList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.callMembers)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(BipeColor.inkSecondary)
            ForEach(call.extraMembers) { tile in
                HStack(spacing: 8) {
                    if let media { AvatarView(name: tile.name, mxcUrl: tile.avatarUrl, loader: media, size: 28, userId: tile.userId) }
                    Text(tile.name).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
                    Spacer()
                    if !tile.microphoneOn {
                        PhosphorIcon.microphoneSlash.labelledImage(BipeStrings.callMuted)
                            .resizable().frame(width: 16, height: 16).foregroundStyle(BipeColor.glyphSecondary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func denied(_ text: String) -> some View {
        VStack(spacing: 12) {
            Text(text)
                .font(.body.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button(BipeStrings.contactsOpenSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            .buttonStyle(.bipeBordered)
            if call.microphoneDenied {
                Button(BipeStrings.appClose, action: close)
                    .buttonStyle(.bipeBordered)
            }
        }
        .padding(.top, 32)
    }

    private var failed: some View {
        VStack(spacing: 12) {
            Text(BipeStrings.callErrorConnect)
                .font(.body.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button(BipeStrings.appClose, action: close)
                .buttonStyle(.bipeBordered)
        }
        .padding(.top, 32)
    }

    private var controls: some View {
        HStack(spacing: 12) {
            CallControlButton(icon: call.microphoneOn ? .microphone : .microphoneSlash, label: BipeStrings.callMicrophone,
                              isOn: call.microphoneOn, action: call.toggleMicrophone)
            CallControlButton(icon: call.cameraOn ? .videoCamera : .videoCameraSlash, label: BipeStrings.callCamera,
                              isOn: call.cameraOn, action: call.toggleCamera)
            if call.cameraOn {
                CallControlButton(icon: .cameraRotate, label: BipeStrings.callCameraFlip, isOn: nil, action: call.flipCamera)
            }
            CallControlButton(icon: call.speakerOn ? .speakerHigh : .speakerLow, label: BipeStrings.callSpeaker,
                              isOn: call.speakerOn, action: call.toggleSpeaker)
            CallRoutePicker()
                .frame(width: BipeTapTarget.minimum, height: BipeTapTarget.minimum)
                .accessibilityLabel(BipeStrings.callSpeaker)
            Button(action: call.end) {
                PhosphorIcon.phoneDisconnect.image.resizable().frame(width: 26, height: 26)
                    .foregroundStyle(BipeColor.inkOnAccent)
                    .frame(width: 60, height: 60)
                    .background(Circle().fill(BipeColor.trustDangerInk))
                    .bipeTapTarget()
            }
            .accessibilityLabel(BipeStrings.callEnd)
        }
    }
}

/// One member's tile: the video, or the avatar with the name, and the muted mark.
private struct CallTileView: View {
    let tile: CallTile
    let media: MediaLoader?
    let tall: Bool

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            BipeColor.surfaceRaised
            if tile.cameraOn, let track = tile.track {
                SwiftUIVideoView(track, layoutMode: .fill)
            } else if let media {
                AvatarView(name: tile.name, mxcUrl: tile.avatarUrl, loader: media, size: 72, userId: tile.userId)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 4) {
                if !tile.microphoneOn {
                    PhosphorIcon.microphoneSlash.labelledImage(BipeStrings.callMuted).resizable().frame(width: 14, height: 14)
                }
                Text(tile.name).font(.caption.weight(.semibold)).lineLimit(1)
            }
            .foregroundStyle(BipeColor.inkPrimary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .bipeMaterial(in: Capsule())
            .padding(8)
        }
        .frame(height: tall ? 260 : 170)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(tile.speaking ? BipeColor.accentFill : .clear, lineWidth: 3))
        .accessibilityElement(children: .combine)
        .accessibilityValue(tile.cameraOn ? "" : BipeStrings.callCameraOff)
    }
}

/// A round call control. `isOn` nil: a plain action.
private struct CallControlButton: View {
    let icon: PhosphorIcon
    let label: String
    let isOn: Bool?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            icon.image.resizable().frame(width: 22, height: 22)
                .foregroundStyle(isOn == false ? BipeColor.inkOnAccent : BipeColor.glyphPrimary)
                .frame(width: 48, height: 48)
                .background(Circle().fill(isOn == false ? BipeColor.glyphSecondary : BipeColor.surfaceRaised))
                .bipeTapTarget()
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(isOn == true ? .isSelected : [])
    }
}

/// The system route picker: earpiece, speaker, Bluetooth, AirPlay.
private struct CallRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        view.tintColor = UIColor(BipeColor.glyphPrimary)
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
