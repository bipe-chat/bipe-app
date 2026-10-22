// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import QuickLook
import SwiftUI

/// Spec timeline-media: the content of an image, video, sticker or file message. The bubble, sender,
/// reply quote, footer and reactions come from the message row of spec timeline-rows.
struct TimelineMediaRow: View {
    let message: TimelineMessage
    let media: MediaMessage
    let loader: MediaLoader
    let isPrivateRoom: Bool
    /// Action open for image, video and sticker: media-viewer.
    let openViewer: () -> Void
    let cancelUpload: () -> Void

    /// Display of spec timeline-media.
    enum Display: Equatable {
        case placeholder
        case tapToShow
        case loading
        case shown
        case failed
    }

    @State private var display = Display.placeholder
    @State private var revealed = false
    @State private var image: UIImage?
    @State private var fileURL: URL?
    @State private var isOpeningFile = false

    private static let maxWidth: CGFloat = 260
    private static let stickerWidth: CGFloat = 160

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            content
            if let caption = media.caption?.nilIfBlank, media.kind != .sticker {
                Text(caption)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: Self.maxWidth, alignment: .leading)
            }
        }
        .accessibilityIdentifier(BipeScreen.timelineMedia.rawValue)
        .quickLookPreview($fileURL)
    }

    @ViewBuilder
    private var content: some View {
        switch media.kind {
        case .file:
            fileCard
        case .image, .video, .sticker:
            visual
        }
    }

    // MARK: Image, video, sticker

    private var visual: some View {
        let width = media.kind == .sticker ? Self.stickerWidth : Self.maxWidth
        let height = min(width / media.aspectRatio, width * 1.4)
        return ZStack {
            if let blurhash = media.blurhash, display != .shown, let placeholder = Blurhash.image(blurhash) {
                Image(uiImage: placeholder)
                    .resizable()
            } else if display != .shown, media.kind != .sticker {
                BipeColor.surfaceSunken
            }
            if display == .shown, let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: media.kind == .sticker ? .fit : .fill)
            }
            overlay
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: media.kind == .sticker ? 0 : 12))
        .contentShape(Rectangle())
        .onTapGesture(perform: tap)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(media.caption?.nilIfBlank.map { "\(media.kindLabel), \($0)" } ?? media.kindLabel)
        .accessibilityAddTraits(.isButton)
        .task(id: "\(media.source.url)#\(revealed)") { await appear() }
    }

    @ViewBuilder
    private var overlay: some View {
        if let progress = media.uploadProgress, message.isOwn, message.sendState == .sending {
            uploadOverlay(progress)
        } else {
            switch display {
            case .tapToShow:
                Text(BipeStrings.appMediaTapToShow)
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .mediaScrim(Capsule())
            case .placeholder, .loading:
                ProgressView()
                    .padding(10)
                    .mediaScrim(Circle())
            case .failed:
                VStack(spacing: 6) {
                    PhosphorIcon.arrowClockwise.image
                        .resizable()
                        .frame(width: 22, height: 22)
                    Text(BipeStrings.appMediaFailed)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                }
                .padding(10)
                .mediaScrim(RoundedRectangle(cornerRadius: 10))
                .padding(8)
            case .shown:
                if media.kind == .video {
                    videoBadge
                }
            }
        }
    }

    private var videoBadge: some View {
        ZStack(alignment: .bottomLeading) {
            PhosphorIcon.play.image
                .resizable()
                .frame(width: 22, height: 22)
                .padding(12)
                .mediaScrim(Circle())
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if let duration = media.durationLabel {
                Text(duration)
                    .font(.caption2.monospacedDigit())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .mediaScrim(Capsule())
                    .padding(8)
            }
        }
    }

    private func uploadOverlay(_ progress: Int) -> some View {
        Button(action: cancelUpload) {
            ZStack {
                Circle()
                    .trim(from: 0, to: CGFloat(progress) / 100)
                    .stroke(BipeColor.inkPrimary, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(4)
                PhosphorIcon.x.image
                    .resizable()
                    .frame(width: 16, height: 16)
            }
            .frame(width: 44, height: 44)
            .mediaScrim(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(BipeStrings.appCancel)
    }

    // MARK: File

    private var fileCard: some View {
        Button(action: tap) {
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(BipeColor.accentWash)
                    if isOpeningFile {
                        ProgressView()
                    } else if let progress = media.uploadProgress, message.sendState == .sending {
                        Circle()
                            .trim(from: 0, to: CGFloat(progress) / 100)
                            .stroke(BipeColor.accentFill, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .padding(3)
                        PhosphorIcon.x.image
                            .resizable()
                            .frame(width: 16, height: 16)
                            .foregroundStyle(BipeColor.accentInk)
                    } else {
                        (display == .failed ? PhosphorIcon.arrowClockwise : PhosphorIcon.file).image
                            .resizable()
                            .frame(width: 20, height: 20)
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(media.filename)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .lineLimit(2)
                        .truncationMode(.middle)
                    if display == .failed {
                        Text(BipeStrings.appMediaFailed)
                            .font(.caption)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    } else if let size = media.sizeLabel {
                        Text(size)
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            .padding(6)
            .frame(maxWidth: Self.maxWidth, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(media.kindLabel), \(media.filename)")
    }

    // MARK: Actions

    /// Action appear: loads the thumbnail (or the image) when display may show it.
    private func appear() async {
        guard media.kind != .file else { return }
        guard revealed || ShowMedia.current.shows(inPrivateRoom: isPrivateRoom) || message.isOwn else {
            display = .tapToShow
            return
        }
        guard display != .shown else { return }
        display = image == nil && media.blurhash == nil ? .loading : .placeholder
        if let loaded = await loader.previewImage(media, maxPixel: media.kind == .sticker ? 480 : 900) {
            image = loaded
            display = .shown
        } else {
            display = .failed
        }
    }

    private func tap() {
        if media.uploadProgress != nil, message.sendState == .sending {
            cancelUpload()
            return
        }
        switch display {
        case .tapToShow:
            // Action tapToShow.
            revealed = true
        case .failed where media.kind != .file:
            // Action retryDownload.
            Task { await appear() }
        default:
            if media.opensInViewer {
                openViewer()
            } else {
                openFile()
            }
        }
    }

    /// Action open for a file: downloads it to the cache and opens the system preview.
    private func openFile() {
        guard !isOpeningFile else { return }
        isOpeningFile = true
        Task {
            defer { isOpeningFile = false }
            if let url = try? await loader.file(media.source, filename: media.filename, mimeType: media.mimeType) {
                display = .placeholder
                fileURL = url
            } else {
                display = .failed
            }
        }
    }
}

extension View {
    /// Light ink on a dark blur over media, in light and dark mode.
    func mediaScrim<S: Shape>(_ shape: S) -> some View {
        foregroundStyle(BipeColor.inkPrimary)
            .tint(BipeColor.inkPrimary)
            .background(.ultraThinMaterial, in: shape)
            .environment(\.colorScheme, .dark)
    }
}
