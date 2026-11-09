// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import AVKit
import BipeKit
import SwiftUI

struct MacVideoView: View {
    let video: MacVideo
    let eventID: String
    let isOwn: Bool
    let isPrivate: Bool
    let fromRequest: Bool
    let richText: BipeMessageText
    let pauseSecondsLeft: (Date) -> Int
    @ObservedObject var media: MacMedia
    @StateObject private var poster = MacVideoPoster()
    @State private var revealedFor: UUID?
    @State private var viewer = false
    @State private var retry = 0

    private var shows: Bool { revealedFor == media.generation || media.showsAutomatically(isOwn: isOwn, isPrivate: isPrivate, fromRequest: fromRequest) }

    var body: some View {
        Group {
            if !shows {
                Button(BipeStrings.appMediaTapToShow) { revealedFor = media.generation }.buttonStyle(.bipeBordered)
            } else if case .failed = poster.phase {
                Button(BipeStrings.appMediaFailed) { retry += 1 }.buttonStyle(.bipeBordered)
            } else {
                Button { viewer = true } label: {
                    ZStack {
                        BipeColor.surfaceSunken
                        if let image = posterImage { Image(nsImage: image).resizable().scaledToFill() }
                        Image(decorative: "play").resizable().scaledToFit().frame(width: 36, height: 36)
                            .padding(12).background(BipeColor.surfaceBase, in: Circle())
                        if let duration = video.durationLabel {
                            VStack { Spacer(); HStack { Spacer(); Text(duration).macFont(.caption).monospacedDigit()
                                .padding(4).background(BipeColor.surfaceBase, in: Capsule()) } }.padding(8)
                        }
                        if case .loading = poster.phase { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(8) }
                    }.frame(width: video.previewSize.width, height: video.previewSize.height)
                        .clipped().clipShape(RoundedRectangle(cornerRadius: MacDesign.corner)).bipeTapTarget()
                }.buttonStyle(.plain).accessibilityLabel(BipeStrings.appMediaVideo)
                    .accessibilityValue(video.durationLabel ?? "")
            }
        }
        .task(id: "\(shows)-\(retry)-\(media.generation)") {
            await poster.load(shows: shows) { try await media.videoThumbnail(video, eventID: eventID) }
        }
        .sheet(isPresented: $viewer) {
            MacVideoViewer(video: video, richText: richText, pauseSecondsLeft: pauseSecondsLeft, media: media)
        }
        .onChange(of: media.generation) { _, _ in viewer = false }
    }

    private var posterImage: NSImage? {
        if case .ready(let image?) = poster.phase { return image }
        return video.content.info?.blurhash.flatMap { Blurhash.image($0) }
    }
}

struct MacVideoViewer: View {
    let video: MacVideo
    let richText: BipeMessageText
    let pauseSecondsLeft: (Date) -> Int
    @ObservedObject var media: MacMedia
    @Environment(\.dismiss) private var dismiss
    @StateObject private var playback = MacVideoPlayback()
    @State private var retry = 0
    @State private var saving: SavePhase = .idle
    @State private var saveTask: Task<Void, Never>?
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    private enum SavePhase { case idle, saving, failed }

    var body: some View {
        VStack(spacing: MacDesign.gap) {
            HStack {
                Text(video.file.name.filename).macFont(.headline).lineLimit(1)
                Spacer()
                Button(BipeStrings.appMediaSave) {
                    saving = .saving
                    saveTask = Task {
                        do { try await media.save(video.file); if !Task.isCancelled { saving = .idle } }
                        catch { if !Task.isCancelled { saving = .failed } }
                    }
                }.disabled(isSaving)
                Button(BipeStrings.appClose) { playback.close(); dismiss() }.keyboardShortcut(.cancelAction)
            }
            Group {
                switch playback.phase {
                case .ready(let session): MacVideoPlayer(player: session.player)
                case .failed: Button(BipeStrings.appMediaFailed) { retry += 1 }.buttonStyle(.bipeBordered)
                default: ProgressView().accessibilityLabel(BipeStrings.appMediaVideo)
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if pauseSecondsLeft(context.date) > 0 { caption } else { caption.textSelection(.enabled) }
            }
            if case .failed = saving { MacNotice(text: BipeStrings.appMediaSaveFailed, danger: true) }
        }.padding(MacDesign.sectionGap)
            .frame(minWidth: 640, idealWidth: 860, minHeight: 480, idealHeight: 680)
            .background(BipeColor.surfaceBase)
            .environment(\.openURL, OpenURLAction { _ in pauseSecondsLeft(.now) > 0 ? .handled : .systemAction })
            .task(id: retry) { await playback.load(media: media) { try await media.download(video.file) } }
            .onDisappear { playback.close(); saveTask?.cancel() }
    }

    private var caption: some View { Text(MacRichText.attributed(richText, bodySize: bodySize)) }
    private var isSaving: Bool { if case .saving = saving { true } else { false } }
}

/// Native playback controls include seeking, sound, keyboard operation and full screen.
struct MacVideoPlayer: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .inline
        view.showsFullScreenToggleButton = true
        view.allowsPictureInPicturePlayback = false
        view.setAccessibilityLabel(BipeStrings.appMediaVideo)
        return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) { view.player = player }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) { view.player?.pause(); view.player = nil }
}

struct MacVideoComposer: View {
    let video: MacPreparedVideo
    @ObservedObject var model: MacTimeline
    let maxUploadSize: () async -> UInt64?
    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            HStack {
                Text(BipeStrings.appMediaVideo).macFont(.title2).fontWeight(.semibold)
                Spacer()
                Button(BipeStrings.appCancel) { model.cancelAttachment() }.keyboardShortcut(.cancelAction)
            }
            if let image = NSImage(contentsOf: video.thumbnail) {
                Image(nsImage: image).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 320)
                    .accessibilityLabel(BipeStrings.appMediaVideo)
            }
            TextField(BipeStrings.appUploadCaptionPlaceholder, text: $model.imageCaption, axis: .vertical)
                .lineLimit(2...5).disabled(model.sending)
            if let error = model.uploadError { MacNotice(text: error, danger: true) }
            HStack {
                if model.sending { ProgressView() }
                Spacer()
                Button(BipeStrings.appUploadSend) { Task { await model.sendAttachment(maxUploadSize: await maxUploadSize()) } }
                    .buttonStyle(.bipeProminent).disabled(model.sending || !model.ready)
                    .keyboardShortcut(.return, modifiers: .command)
            }
        }.padding(MacDesign.sectionGap).frame(width: 560).background(BipeColor.surfaceBase)
    }
}
