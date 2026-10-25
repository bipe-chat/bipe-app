// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import Combine
import MatrixRustSDK
import UIKit

/// The player of the voice-message rows of one chat. One row plays at a time (action togglePlay).
@MainActor
final class VoicePlayer: NSObject, ObservableObject {
    /// playback of spec voice-message.
    enum Playback: Equatable {
        case idle
        case loading
        case playing
        case paused
        case failed
    }

    @Published private(set) var rowId: String?
    @Published private(set) var playback = Playback.idle
    /// Played part of the current row, 0 to 1.
    @Published private(set) var progress = 0.0
    @Published private(set) var failedRows: Set<String> = []

    private var player: AVAudioPlayer?
    private var ticker: AnyCancellable?
    private var observers: [NSObjectProtocol] = []
    /// Positions of rows that were paused, so a row resumes where it stopped.
    private var positions: [String: (time: TimeInterval, fraction: Double)] = [:]

    override init() {
        super.init()
        let center = NotificationCenter.default
        // A call or other audio stops playback (spec voice-message notes).
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.pause() }
        })
        observers.append(center.addObserver(forName: UIDevice.proximityStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.routeForProximity() }
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    func playback(of id: String) -> Playback {
        if rowId == id { return playback }
        return failedRows.contains(id) ? .failed : .idle
    }

    func progress(of id: String) -> Double {
        if rowId == id { return progress }
        return positions[id]?.fraction ?? 0
    }

    /// Action togglePlay: downloads the audio when needed, then plays or pauses. Starting one row stops any other.
    func togglePlay(id: String, voice: VoiceMessage, loader: MediaLoader) {
        if rowId == id, playback == .playing {
            pause()
            return
        }
        if rowId == id, playback == .paused, let player {
            start(player)
            return
        }
        stop()
        rowId = id
        playback = .loading
        failedRows.remove(id)
        Task {
            do {
                let url = try await playableFile(voice, loader: loader)
                guard rowId == id else { return }
                let player = try AVAudioPlayer(contentsOf: url)
                player.delegate = self
                player.prepareToPlay()
                if let position = positions[id] {
                    player.currentTime = position.time
                }
                self.player = player
                start(player)
            } catch {
                if Self.isGone(error) { VoiceMediaExpiry.shared.markExpired(voice.source.url) }
                guard rowId == id else { return }
                failedRows.insert(id)
                playback = .failed
            }
        }
    }

    /// Action seek.
    func seek(id: String, fraction: Double, duration: TimeInterval) {
        let fraction = max(0, min(1, fraction))
        guard rowId == id, let player else {
            positions[id] = (duration * fraction, fraction)
            objectWillChange.send()
            return
        }
        player.currentTime = player.duration * fraction
        progress = fraction
    }

    func pause() {
        guard let player, playback == .playing else { return }
        player.pause()
        if let rowId { positions[rowId] = (player.currentTime, progress) }
        playback = .paused
        stopTicker()
    }

    func stop() {
        player?.stop()
        player = nil
        stopTicker()
        rowId = nil
        playback = .idle
        progress = 0
    }

    private func start(_ player: AVAudioPlayer) {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
        UIDevice.current.isProximityMonitoringEnabled = true
        player.play()
        playback = .playing
        ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self, let player = self.player, player.duration > 0 else { return }
            self.progress = player.currentTime / player.duration
        }
    }

    private func stopTicker() {
        ticker = nil
        UIDevice.current.isProximityMonitoringEnabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Speaker by default; the earpiece when the phone is at the ear.
    private func routeForProximity() {
        guard playback == .playing else { return }
        let session = AVAudioSession.sharedInstance()
        if UIDevice.current.proximityState {
            try? session.setCategory(.playAndRecord, mode: .voiceChat)
            try? session.overrideOutputAudioPort(.none)
        } else {
            try? session.setCategory(.playback, mode: .spokenAudio)
        }
    }

    /// The media repository answered 404 (M_NOT_FOUND): the server deleted the audio after its time (ADR 0164).
    private static func isGone(_ error: Error) -> Bool {
        guard let error = error as? ClientError else { return false }
        switch error {
        case .MatrixApi(kind: .notFound, code: _, msg: _, details: _): return true
        // Media downloads can report the HTTP error as text.
        case .Generic(let msg, _): return msg.contains("M_NOT_FOUND")
        default: return false
        }
    }

    /// The downloaded file, decoded to PCM first when it is Ogg Opus. The listener transcript reads it too (bipe#500).
    func playableFile(_ voice: VoiceMessage, loader: MediaLoader) async throws -> URL {
        let file = try await loader.file(voice.source, filename: voice.filename, mimeType: voice.mimeType)
        guard OggOpusDecoder.isOgg(file) else { return file }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Voice", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let name = Data(voice.source.url.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
        let decoded = cache.appendingPathComponent(name).appendingPathExtension("caf")
        if !FileManager.default.fileExists(atPath: decoded.path) {
            try await Task.detached(priority: .userInitiated) {
                try OggOpusDecoder.decode(file, to: decoded)
            }.value
        }
        return decoded
    }
}

extension VoicePlayer: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            if let rowId = self.rowId { self.positions[rowId] = nil }
            self.stop()
        }
    }
}
