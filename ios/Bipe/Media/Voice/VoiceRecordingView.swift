// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec voice-recording: replaces the composer field while the person records and checks the voice message.
struct VoiceRecordingView: View {
    @ObservedObject var model: VoiceRecordingModel
    /// Back to the text field after delete or send.
    var close: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.microphoneDenied {
                microphoneDenied
            } else {
                switch model.phase {
                case .recording, .paused:
                    recordingLine
                case .preview, .sending:
                    previewLine
                    transcriptBlock
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .accessibilityIdentifier(BipeScreen.voiceRecording.rawValue)
        .task { await model.start() }
        .onChange(of: model.isClosed) { _, closed in
            if closed { close() }
        }
    }

    private var durationLabel: String {
        MediaFormat.duration(TimeInterval(model.duration) / 1000)
    }

    private var recordingLine: some View {
        HStack(spacing: 10) {
            iconButton(.trash, label: BipeStrings.appVoiceDelete, tint: BipeColor.trustDangerInk) { model.delete() }
            VStack(alignment: .leading, spacing: 2) {
                Text(model.phase == .recording ? BipeStrings.appVoiceRecording : BipeStrings.appVoiceResume)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(model.phase == .recording ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
                Text(durationLabel)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(BipeColor.inkPrimary)
                    .accessibilityLabel(BipeStrings.appVoiceDuration(durationLabel))
            }
            Waveform(bars: model.waveform.map { UInt16($0) }, progress: 0) { _ in }
                .frame(height: 28)
                .allowsHitTesting(false)
            if model.phase == .recording {
                iconButton(.pause, label: BipeStrings.appVoicePause, tint: BipeColor.glyphPrimary) { model.pause() }
            } else {
                iconButton(.microphone, label: BipeStrings.appVoiceResume, tint: BipeColor.trustDangerInk) { model.resume() }
            }
            iconButton(.stop, label: BipeStrings.appVoiceStop, tint: BipeColor.inkOnAccent, fill: BipeColor.accentFill) { model.stop() }
        }
    }

    private var previewLine: some View {
        HStack(spacing: 10) {
            iconButton(.trash, label: BipeStrings.appVoiceDelete, tint: BipeColor.trustDangerInk) { model.delete() }
            iconButton(model.isPlaying ? .pause : .play,
                       label: model.isPlaying ? BipeStrings.appVoicePause : BipeStrings.appVoicePlay,
                       tint: BipeColor.accentInk) { model.play() }
            Waveform(bars: model.waveform.map { UInt16($0) }, progress: 0) { _ in }
                .frame(height: 28)
                .allowsHitTesting(false)
            Text(durationLabel)
                .font(.caption.monospacedDigit())
                .foregroundStyle(BipeColor.inkSecondary)
                .accessibilityLabel(BipeStrings.appVoiceDuration(durationLabel))
            Button {
                Task { await model.send() }
            } label: {
                Group {
                    if model.phase == .sending {
                        ProgressView().tint(BipeColor.inkOnAccent)
                    } else {
                        PhosphorIcon.paperPlaneRight.image.resizable()
                    }
                }
                .frame(width: 22, height: 22)
                .padding(9)
                .foregroundStyle(BipeColor.inkOnAccent)
                .background(Circle().fill(BipeColor.accentFill))
                .bipeTapTarget()
            }
            .disabled(model.phase == .sending)
            .accessibilityLabel(BipeStrings.appTimelineSend)
        }
    }

    /// The transcript the phone made (ADR 0023): the person can correct or clear it before sending.
    @ViewBuilder
    private var transcriptBlock: some View {
        switch model.transcript {
        case .off:
            EmptyView()
        case .working:
            HStack(spacing: 6) {
                ProgressView()
                Text(BipeStrings.voiceTranscribing)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        case .ready:
            VStack(alignment: .leading, spacing: 4) {
                Text(BipeStrings.voiceTranscriptTitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BipeColor.inkSecondary)
                TextField(BipeStrings.voiceTranscriptPlaceholder,
                          text: Binding(get: { model.transcriptText }, set: { model.editTranscript($0) }),
                          axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceSunken))
                    .foregroundStyle(BipeColor.inkPrimary)
            }
        case .unavailable:
            Text(BipeStrings.voiceTranscriptUnavailable)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
        }
    }

    private var microphoneDenied: some View {
        HStack(spacing: 10) {
            Text(BipeStrings.microphonePermissionBody)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
            Spacer()
            Button(BipeStrings.contactsOpenSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(BipeColor.accentInk)
            iconButton(.x, label: BipeStrings.appCancel, tint: BipeColor.glyphSecondary) { model.delete() }
        }
    }

    private func iconButton(_ icon: PhosphorIcon, label: String, tint: Color, fill: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            icon.image
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(tint)
                .padding(10)
                .background(Circle().fill(fill ?? BipeColor.surfaceSunken))
                .bipeTapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
