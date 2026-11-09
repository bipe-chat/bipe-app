// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import AVFoundation
import Combine
import Foundation

/// A player and its SDK-decrypted file have the same lifetime. Closing also removes the item from AVFoundation.
final class MacVideoSession {
    let id: UUID
    let player: AVPlayer
    private let lease: MacDownloadedFile
    private var observation: NSKeyValueObservation?

    init(id: UUID, lease: MacDownloadedFile, asset: AVAsset, failed: @escaping @MainActor () -> Void) {
        self.id = id; self.lease = lease
        let item = AVPlayerItem(asset: asset)
        player = AVPlayer(playerItem: item)
        observation = item.observe(\.status, options: [.new]) { item, _ in
            if item.status == .failed { Task { @MainActor in failed() } }
        }
    }

    func close() {
        observation?.invalidate(); observation = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        lease.remove()
    }
    deinit { close() }
}

@MainActor
final class MacVideoPlayback: ObservableObject {
    enum Phase { case idle, loading(UUID), ready(MacVideoSession), failed }
    @Published private(set) var phase: Phase = .idle
    private var accountChange: AnyCancellable?

    func load(media: MacMedia? = nil, download: () async throws -> MacDownloadedFile) async {
        close()
        let revision = UUID()
        phase = .loading(revision)
        accountChange = media?.$generation.dropFirst().sink { [weak self] _ in self?.close() }
        do {
            let lease = try await download()
            do {
                try Task.checkCancellation()
                guard isLoading(revision), lease.file.isFileURL else { throw CancellationError() }
                let asset = try VideoPreparation.localAsset(lease.file)
                guard try await asset.load(.isPlayable), !(try await asset.loadTracks(withMediaType: .video)).isEmpty else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                try Task.checkCancellation()
                guard isLoading(revision) else { throw CancellationError() }
                let session = MacVideoSession(id: revision, lease: lease, asset: asset) { [weak self] in
                    guard let self, case .ready(let session) = self.phase, session.id == revision else { return }
                    session.close(); self.phase = .failed
                }
                phase = .ready(session)
                session.player.play()
            } catch { lease.remove(); throw error }
        } catch {
            guard isLoading(revision) else { return }
            phase = error is CancellationError ? .idle : .failed
        }
    }

    func close() {
        if case .ready(let session) = phase { session.close() }
        phase = .idle
        accountChange = nil
    }
    private func isLoading(_ id: UUID) -> Bool { if case .loading(let current) = phase { current == id } else { false } }
}

@MainActor
final class MacVideoPoster: ObservableObject {
    enum Phase { case idle, loading(UUID), ready(NSImage?), failed }
    @Published private(set) var phase: Phase = .idle

    func load(shows: Bool, thumbnail: () async throws -> NSImage?) async {
        phase = .idle
        guard shows else { return }
        let revision = UUID()
        phase = .loading(revision)
        do {
            let image = try await thumbnail()
            try Task.checkCancellation()
            guard case .loading(let current) = phase, current == revision else { return }
            phase = .ready(image)
        } catch {
            guard case .loading(let current) = phase, current == revision else { return }
            phase = error is CancellationError ? .idle : .failed
        }
    }
}
