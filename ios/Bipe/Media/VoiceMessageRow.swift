// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec voice-message: play and pause, the waveform with progress, the duration and the sender's transcript.
/// The bubble, sender, footer and reactions come from the message row of spec timeline-rows.
struct VoiceMessageRow: View {
    let message: TimelineMessage
    let voice: VoiceMessage
    let loader: MediaLoader
    @ObservedObject var player: VoicePlayer
    /// Expiry and keep state of the account (ADR 0164).
    @ObservedObject var voiceMedia: VoiceMediaStore
    /// The scam card shows on this message (spec scam-warning).
    var hasScamWarning = false
    @ObservedObject private var listener = ListenerTranscripts.shared
    @ObservedObject private var expiry = VoiceMediaExpiry.shared

    @State private var transcriptExpanded = false

    /// Over this many characters the transcript folds to 3 lines (same rule as the forks).
    private static let foldLength = 140

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !voice.isVoice {
                Text(voice.filename)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            if expiry.isExpired(voice) {
                // State media expired: no player and no waveform; the transcript stays.
                Text(BipeStrings.voiceExpired)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                playerLine
            }
            if playback == .failed, !expiry.isExpired(voice) {
                Text(BipeStrings.appMediaFailed)
                    .font(.caption)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            if let transcript = voice.transcript {
                transcriptBlock(transcript)
            } else if voice.isVoice, !message.isOwn, !expiry.isExpired(voice) {
                listenerTranscript
            }
            switch voiceMedia.kept[voice.source.url] {
            case .kept?:
                Text(BipeStrings.voiceKept)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            case .scam?:
                Text(BipeStrings.voiceKeptScam)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            case nil:
                EmptyView()
            }
        }
        .frame(maxWidth: 256, alignment: .leading)
        .accessibilityIdentifier(BipeScreen.voiceMessage.rawValue)
        .task(id: "\(voice.source.url)|\(message.sendState == .sent)") { await expiryTasks() }
    }

    /// Actions register and keepScam of spec voice-message (ADR 0164).
    private func expiryTasks() async {
        guard voice.isVoice else { return }
        await voiceMedia.load()
        if message.isOwn, message.sendState == .sent {
            await voiceMedia.register(url: voice.source.url, transcript: voice.transcript != nil)
        } else if !message.isOwn, hasScamWarning {
            await voiceMedia.keepForScam(url: voice.source.url)
        }
    }

    private var playback: VoicePlayer.Playback {
        player.playback(of: message.id)
    }

    private var playerLine: some View {
        HStack(spacing: 10) {
            Button {
                player.togglePlay(id: message.id, voice: voice, loader: loader)
            } label: {
                ZStack {
                    Circle().fill(BipeColor.accentFill)
                    if playback == .loading {
                        ProgressView().tint(BipeColor.inkOnAccent)
                    } else {
                        (playback == .playing ? PhosphorIcon.pause : PhosphorIcon.play).image
                            .resizable()
                            .frame(width: 18, height: 18)
                            .foregroundStyle(BipeColor.inkOnAccent)
                    }
                }
                .frame(width: 40, height: 40)
                .bipeTapTarget()
            }
            .buttonStyle(.plain)
            .accessibilityLabel(playback == .playing ? BipeStrings.appVoicePause : BipeStrings.appVoicePlay)

            Waveform(bars: voice.waveform, progress: player.progress(of: message.id)) { fraction in
                // Action seek.
                player.seek(id: message.id, fraction: fraction, duration: voice.duration)
            }
            .frame(height: 32)
            .accessibilityLabel(BipeStrings.appMediaVoice)

            Text(voice.durationLabel)
                .font(.caption.monospacedDigit())
                .foregroundStyle(BipeColor.inkSecondary)
        }
    }

    /// Spec voice-message, action transcribe and state localTranscript (bipe#500): on a tap, on this phone only.
    @ViewBuilder
    private var listenerTranscript: some View {
        switch listener.state(of: message.id) {
        case .none, .failed:
            VStack(alignment: .leading, spacing: 2) {
                if listener.state(of: message.id) == .failed {
                    Text(BipeStrings.voiceTranscribeFailed)
                        .font(.caption)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Button {
                    let id = message.id
                    Task { await listener.transcribe(id: id) { try await player.playableFile(voice, loader: loader) } }
                } label: {
                    Text(BipeStrings.voiceTranscribe)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.accentInk)
                        .bipeTapTarget()
                }
                .buttonStyle(.plain)
            }
        case .working:
            HStack(spacing: 6) {
                ProgressView()
                Text(BipeStrings.voiceTranscribing)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            .accessibilityElement(children: .combine)
        case .ready(let text):
            VStack(alignment: .leading, spacing: 4) {
                transcriptBlock(text)
                Text(BipeStrings.voiceTranscribedHere)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        case .unavailable:
            Text(BipeStrings.voiceTranscribeUnavailable)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
        case .denied:
            Text(BipeStrings.voiceTranscribeDenied)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
        }
    }

    /// The transcript block of ADR 0023: the title, then the text in 3 lines; show more or less over 140 characters.
    private func transcriptBlock(_ transcript: String) -> some View {
        let folds = transcript.count > Self.foldLength
        return VStack(alignment: .leading, spacing: 2) {
            Text(BipeStrings.voiceTranscriptTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(BipeColor.inkSecondary)
            Text(transcript)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(folds && !transcriptExpanded ? 3 : nil)
                .textSelection(.enabled)
            if folds {
                // Action toggleTranscript.
                Button {
                    transcriptExpanded.toggle()
                } label: {
                    Text(transcriptExpanded ? BipeStrings.actionShowLess : BipeStrings.actionShowMore)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BipeColor.accentInk)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Bars of a voice message, 0 to 1024 each, with the played part in the accent colour. A drag seeks.
struct Waveform: View {
    let bars: [UInt16]
    let progress: Double
    let seek: (Double) -> Void

    private static let barCount = 36

    var body: some View {
        GeometryReader { proxy in
            let values = Self.resample(bars, to: Self.barCount)
            HStack(alignment: .center, spacing: 2) {
                ForEach(values.indices, id: \.self) { index in
                    let played = Double(index) / Double(values.count) < progress
                    Capsule()
                        .fill(played ? BipeColor.accentFill : BipeColor.lineStrong)
                        .frame(height: max(3, proxy.size.height * values[index]))
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onEnded { value in
                seek(value.location.x / max(1, proxy.size.width))
            })
        }
        // VoiceOver seeks with a swipe up or down, a tenth at a time (bipe#499).
        .accessibilityElement()
        .accessibilityLabel(BipeStrings.a11yVoicePosition)
        .accessibilityValue(progress.formatted(.percent.precision(.fractionLength(0))))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: seek(min(1, progress + 0.1))
            case .decrement: seek(max(0, progress - 0.1))
            @unknown default: break
            }
        }
    }

    /// Averages the event bars into count values from 0 to 1. Empty draws a flat line.
    static func resample(_ bars: [UInt16], to count: Int) -> [CGFloat] {
        guard !bars.isEmpty else { return Array(repeating: 0, count: count) }
        return (0..<count).map { index in
            let start = index * bars.count / count
            let end = max(start + 1, (index + 1) * bars.count / count)
            let slice = bars[start..<min(end, bars.count)]
            let mean = slice.reduce(0.0) { $0 + Double($1) } / Double(max(1, slice.count))
            return CGFloat(min(1, mean / 1024))
        }
    }
}
