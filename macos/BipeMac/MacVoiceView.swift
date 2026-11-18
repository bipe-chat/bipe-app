// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Combine
import MatrixRustSDK
import SwiftUI

/// An audio message on the Mac (spec voice-message, bipe-chat/bipe#1220).
struct MacVoice: Equatable {
    /// The MSC3245 voice marker: a recorded voice message, not an audio file.
    let isVoice: Bool
    let filename: String
    let duration: TimeInterval?
    var source: MediaSource? = nil
    var mimeType: String? = nil

    /// A voice message never shows its file name (voice-<seconds>.ogg); an audio file does, as on web.
    var title: String { isVoice || filename.isEmpty ? BipeStrings.appMediaVoice : filename }
    var durationLabel: String? { duration.flatMap { $0 > 0 ? MediaFormat.duration($0) : nil } }
    /// A safe name for the downloaded copy: never a path from the sender.
    var safeFilename: String {
        let name = (filename as NSString).lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty || name.hasPrefix(".") ? "voice.ogg" : name
    }

    static func == (lhs: MacVoice, rhs: MacVoice) -> Bool {
        lhs.isVoice == rhs.isVoice && lhs.filename == rhs.filename && lhs.duration == rhs.duration
            && lhs.mimeType == rhs.mimeType && lhs.source?.url() == rhs.source?.url()
    }
}

/// The player of the voice-message rows (spec voice-message). One row plays at a time on this Mac; the speed is one
/// setting for every row (bipe#824). The decrypted file and its decoded copy live in a private folder that goes when
/// playback stops.
@MainActor
final class MacVoicePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = MacVoicePlayer()

    enum Playback: Equatable { case idle, loading, playing, paused, failed }

    @Published private(set) var rowID: String?
    @Published private(set) var playback = Playback.idle
    @Published private(set) var progress = 0.0
    @Published private(set) var failedRows: Set<String> = []
    @Published private(set) var speed = VoicePlaybackSpeed(rawValue: UserDefaults.standard.string(forKey: VoicePlaybackSpeed.key) ?? "") ?? .normal
    private var player: AVAudioPlayer?
    private var file: MacDownloadedFile?
    private var ticker: AnyCancellable?
    private var attempt = UUID()

    func playback(of id: String) -> Playback {
        if rowID == id { return playback }
        return failedRows.contains(id) ? .failed : .idle
    }

    func progress(of id: String) -> Double { rowID == id ? progress : 0 }

    /// Action togglePlay: downloads and decodes the audio when needed, then plays or pauses.
    func togglePlay(id: String, voice: MacVoice, media: MacMedia) {
        if rowID == id, playback == .playing { pause(); return }
        if rowID == id, playback == .paused, let player { start(player); return }
        guard let source = voice.source else { return }
        stop()
        rowID = id
        playback = .loading
        failedRows.remove(id)
        let attempt = UUID()
        self.attempt = attempt
        Task {
            do {
                let downloaded = try await media.download(source: source, filename: voice.safeFilename,
                                                          mimeType: voice.mimeType ?? "audio/ogg")
                var url = downloaded.file
                if OggOpusDecoder.isOgg(url) {
                    let decoded = downloaded.folder.appendingPathComponent("voice.caf")
                    try await Task.detached(priority: .userInitiated) { try OggOpusDecoder.decode(url, to: decoded) }.value
                    url = decoded
                }
                guard self.attempt == attempt else { return }
                let player = try AVAudioPlayer(contentsOf: url)
                player.delegate = self
                player.enableRate = true
                player.prepareToPlay()
                self.file = downloaded
                self.player = player
                start(player)
            } catch {
                guard self.attempt == attempt else { return }
                failedRows.insert(id)
                playback = .failed
            }
        }
    }

    /// Action cycleSpeed: a playing row changes speed at once.
    func cycleSpeed() {
        speed = speed.next
        UserDefaults.standard.set(speed.rawValue, forKey: VoicePlaybackSpeed.key)
        player?.rate = speed.rate
    }

    /// Action seek, on the playing or paused row.
    func seek(id: String, fraction: Double) {
        guard rowID == id, let player else { return }
        let fraction = max(0, min(1, fraction))
        player.currentTime = player.duration * fraction
        progress = fraction
    }

    func pause() {
        guard let player, playback == .playing else { return }
        player.pause()
        playback = .paused
        ticker = nil
    }

    func stop() {
        attempt = UUID()
        player?.stop()
        player = nil
        file = nil
        ticker = nil
        rowID = nil
        playback = .idle
        progress = 0
    }

    private func start(_ player: AVAudioPlayer) {
        player.rate = speed.rate
        player.play()
        playback = .playing
        ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self, let player = self.player, player.duration > 0 else { return }
            self.progress = player.currentTime / player.duration
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

struct MacVoiceView: View {
    let voice: MacVoice
    let rowID: String
    @ObservedObject var media: MacMedia
    @ObservedObject private var player = MacVoicePlayer.shared

    private var playback: MacVoicePlayer.Playback { player.playback(of: rowID) }

    var body: some View {
        HStack(spacing: 12) {
            Button { player.togglePlay(id: rowID, voice: voice, media: media) } label: {
                Group {
                    if playback == .loading {
                        ProgressView().controlSize(.regular)
                    } else {
                        Image(decorative: playback == .playing ? "pause" : "play").renderingMode(.template).resizable().scaledToFit()
                            .frame(width: 22, height: 22).foregroundStyle(BipeColor.accentInk)
                    }
                }.frame(width: 36, height: 36).bipeTapTarget()
            }
            .buttonStyle(.plain)
            .disabled(voice.source == nil)
            .accessibilityLabel(playback == .playing ? BipeStrings.appVoicePause : BipeStrings.appVoicePlay)
            VStack(alignment: .leading, spacing: 4) {
                Text(voice.title).macFont(.body).fontWeight(.semibold).lineLimit(2)
                Slider(value: Binding(get: { player.progress(of: rowID) }, set: { player.seek(id: rowID, fraction: $0) }))
                    .disabled(player.rowID != rowID)
                    .frame(minWidth: 140)
                    .accessibilityLabel(voice.title)
                HStack {
                    if playback == .failed {
                        Text(BipeStrings.appMediaFailed).macFont(.caption).foregroundStyle(BipeColor.trustDangerInk)
                    } else if let length = voice.durationLabel {
                        Text(length).macFont(.caption).monospacedDigit().foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer(minLength: 8)
                    // Action cycleSpeed (bipe#824): 1x, 1.5x, 2x.
                    Button(BipeStrings.voiceSpeed(player.speed.label)) { player.cycleSpeed() }
                        .buttonStyle(.link).macFont(.caption)
                        .accessibilityLabel(BipeStrings.voiceSpeedLabel(player.speed.label))
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}
