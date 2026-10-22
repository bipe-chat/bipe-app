// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import Combine
import Foundation
import MatrixRustSDK

/// Spec voice-recording: records in the composer, with pause and resume, then a preview with the
/// transcript the phone made. Send puts the transcript in the caption of the voice event (ADR 0023).
@MainActor
final class VoiceRecordingModel: NSObject, ObservableObject {
    enum Phase: Equatable {
        case recording
        case paused
        case preview
        case sending
    }

    /// transcript of spec voice-recording.
    enum Transcript: Equatable {
        case off
        case working
        case ready
        case unavailable
    }

    /// Shorter recordings go back to the composer with bipe_app_voice_too_short.
    static let minimumDuration: TimeInterval = 1
    /// About 100 samples for the bubble.
    static let waveformSamples = 100

    @Published private(set) var phase = Phase.recording
    /// Recorded length in milliseconds.
    @Published private(set) var duration = 0
    /// 0 to 1024 each; the live levels while recording, about 100 samples after stop.
    @Published private(set) var waveform: [Int] = []
    @Published private(set) var transcript = Transcript.off
    @Published var transcriptText = ""
    @Published private(set) var microphoneDenied = false
    @Published private(set) var tooShort = false
    @Published private(set) var isPlaying = false
    /// true when the recording ended: the composer goes back to text.
    @Published private(set) var isClosed = false

    private let timeline: Timeline
    private let replyToEventId: String?
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var meter: AnyCancellable?
    private var levels: [Float] = []
    private var transcription: Task<Void, Never>?
    private let folder: URL
    private var cafURL: URL { folder.appendingPathComponent("voice.caf") }

    init(timeline: Timeline, replyToEventId: String?) throws {
        self.timeline = timeline
        self.replyToEventId = replyToEventId
        folder = try MediaPreparation.workFolder()
        super.init()
    }

    /// Action start: asks for the microphone the first time, then records Opus, mono.
    func start() async {
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else {
            microphoneDenied = true
            return
        }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            // Opus at 24 kbit/s: a lower bitrate saves data on Brazilian carriers (spec voice-recording notes).
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatOpus,
                AVSampleRateKey: 48000,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 24000,
            ]
            let recorder = try AVAudioRecorder(url: cafURL, settings: settings)
            recorder.isMeteringEnabled = true
            guard recorder.record() else { throw CocoaError(.fileWriteUnknown) }
            self.recorder = recorder
            phase = .recording
            startMeter()
        } catch {
            isClosed = true
        }
    }

    /// Action pause.
    func pause() {
        guard phase == .recording else { return }
        recorder?.pause()
        meter = nil
        phase = .paused
    }

    /// Action resume: records on into the same file.
    func resume() {
        guard phase == .paused, let recorder, recorder.record() else { return }
        phase = .recording
        startMeter()
    }

    /// Action stop: under 1 second goes back with bipe_app_voice_too_short; else preview, and the transcript starts.
    func stop() {
        guard phase == .recording || phase == .paused, let recorder else { return }
        let length = recorder.currentTime
        recorder.stop()
        meter = nil
        duration = Int(length * 1000)
        guard length >= Self.minimumDuration else {
            tooShort = true
            delete()
            return
        }
        waveform = Self.resample(levels, to: Self.waveformSamples)
        phase = .preview
        transcription = Task { await transcribe() }
    }

    /// Action play: plays or pauses the recording.
    func play() {
        guard phase == .preview else { return }
        if let player, player.isPlaying {
            player.pause()
            isPlaying = false
            return
        }
        if player == nil {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            player = try? AVAudioPlayer(contentsOf: cafURL)
            player?.delegate = self
        }
        isPlaying = player?.play() ?? false
    }

    /// Action editTranscript.
    func editTranscript(_ text: String) {
        transcriptText = text
    }

    /// Action delete: drops the file and the transcript.
    func delete() {
        transcription?.cancel()
        recorder?.stop()
        recorder?.deleteRecording()
        player?.stop()
        meter = nil
        try? FileManager.default.removeItem(at: folder)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isClosed = true
    }

    /// Action send: the voice attachment with the transcript as caption when it is ready and not blank.
    /// A transcript still working is dropped: the user never waits for it (ADR 0023).
    func send() async {
        guard phase == .preview else { return }
        phase = .sending
        player?.stop()
        transcription?.cancel()
        let caption = transcript == .ready ? transcriptText.nilIfBlank : nil
        let ogg = folder.appendingPathComponent("voice-\(Int(Date().timeIntervalSince1970)).ogg")
        do {
            try OggOpusWriter.remux(caf: cafURL, to: ogg)
            let params = UploadParameters(source: .file(filename: ogg.path),
                                          caption: caption,
                                          formattedCaption: nil,
                                          mentions: nil,
                                          inReplyTo: replyToEventId,
                                          extraContentJson: nil)
            let info = AudioInfo(duration: TimeInterval(duration) / 1000, size: MediaPreparation.fileSize(ogg), mimetype: "audio/ogg")
            _ = try timeline.sendVoiceMessage(params: params,
                                              audioInfo: info,
                                              waveform: waveform.map { Float($0) / 1024 })
        } catch {
            // The send queue shows a failed upload in the timeline; a failed remux sends nothing.
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isClosed = true
    }

    private func transcribe() async {
        guard await VoiceTranscriber.authorize(), let recognizer = VoiceTranscriber.recognizer() else {
            transcript = .off
            return
        }
        transcript = .working
        let text = await VoiceTranscriber.transcribe(cafURL, with: recognizer)
        guard !Task.isCancelled, phase == .preview else { return }
        if let text {
            transcriptText = text
            transcript = .ready
        } else {
            transcript = .unavailable
        }
    }

    private func startMeter() {
        meter = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self, let recorder = self.recorder else { return }
            recorder.updateMeters()
            // dB from -60 to 0 as 0 to 1.
            let level = max(0, min(1, (recorder.averagePower(forChannel: 0) + 60) / 60))
            self.levels.append(level)
            self.duration = Int(recorder.currentTime * 1000)
            self.waveform = Self.resample(Array(self.levels.suffix(40)), to: 40)
        }
    }

    static func resample(_ levels: [Float], to count: Int) -> [Int] {
        guard !levels.isEmpty else { return [] }
        return (0..<count).map { index in
            let start = index * levels.count / count
            let end = max(start + 1, (index + 1) * levels.count / count)
            let slice = levels[min(start, levels.count - 1)..<min(end, levels.count)]
            let peak = slice.max() ?? 0
            return Int(peak * 1024)
        }
    }
}

extension VoiceRecordingModel: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.isPlaying = false }
    }
}
