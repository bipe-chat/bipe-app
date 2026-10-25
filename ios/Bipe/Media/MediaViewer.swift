// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVKit
import BipeKit
import Phosphor
import Photos
import SwiftUI

/// Spec media-viewer: full screen photo or video, swipe through the chat's media, share and save.
struct MediaViewer: View {
    @ObservedObject var model: ChatTimelineModel
    let loader: MediaLoader
    let close: () -> Void

    @State var rowId: String
    @State private var chromeHidden = false
    @State private var shareURL: URL?
    @State private var notice: String?
    @State private var askGalleryPermission = false
    @State private var isWorking = false

    /// The images and videos of the loaded timeline, oldest first.
    private var items: [TimelineMessage] {
        model.messages.filter { $0.media?.opensInViewer == true }
    }

    private var current: TimelineMessage? {
        items.first { $0.id == rowId }
    }

    var body: some View {
        ZStack {
            BipeColor.surfaceBase.ignoresSafeArea()
            TabView(selection: $rowId) {
                ForEach(items) { item in
                    if let media = item.media {
                        MediaPage(media: media, loader: loader)
                            .tag(item.id)
                    }
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .onTapGesture { chromeHidden.toggle() }
            .accessibilityAction(named: BipeStrings.a11yShowControls) { chromeHidden.toggle() }
            .gesture(DragGesture(minimumDistance: 40).onEnded { value in
                // Swipe down closes (action close).
                if value.translation.height > 120, abs(value.translation.width) < 80 {
                    close()
                }
            })
            if !chromeHidden {
                chrome
            }
            if let notice {
                Text(notice)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .mediaScrim(Capsule())
                    .transition(.opacity)
            }
        }
        .environment(\.colorScheme, .dark)
        .accessibilityIdentifier(BipeScreen.mediaViewer.rawValue)
        .onChange(of: rowId) { _, id in
            // Action swipe: at the oldest loaded media, back-paginate.
            if id == items.first?.id {
                Task { await model.loadHistory() }
            }
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL {
                ShareSheet(items: [shareURL])
            }
        }
        .alert(BipeStrings.appMediaGalleryPermission, isPresented: $askGalleryPermission) {
            Button(BipeStrings.contactsOpenSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: close) {
                    PhosphorIcon.x.image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .padding(10)
                        .bipeTapTarget()
                }
                .accessibilityLabel(BipeStrings.appClose)
                if let current {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(current.senderName)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(current.sentAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                Spacer()
            }
            .foregroundStyle(BipeColor.inkPrimary)
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
            .bipeMaterial(in: Rectangle())

            Spacer()

            VStack(alignment: .leading, spacing: 8) {
                if let caption = current?.media?.caption?.nilIfBlank {
                    ScrollView {
                        Text(caption)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 120)
                    .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 32) {
                    Spacer()
                    barButton(PhosphorIcon.shareNetwork, label: BipeStrings.appMediaShare, action: share)
                    barButton(PhosphorIcon.downloadSimple, label: BipeStrings.appMediaSave, action: save)
                    Spacer()
                }
            }
            .padding(16)
            .bipeMaterial(in: Rectangle())
        }
    }

    private func barButton(_ icon: PhosphorIcon, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            icon.image
                .resizable()
                .frame(width: 24, height: 24)
                .padding(8)
                .foregroundStyle(BipeColor.inkPrimary)
                .bipeTapTarget()
        }
        .disabled(isWorking)
        .accessibilityLabel(label)
    }

    /// Action share: downloads the full file to the cache and opens the share sheet.
    private func share() {
        guard let media = current?.media else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            if let url = try? await loader.file(media.source, filename: media.filename, mimeType: media.mimeType) {
                shareURL = url
            } else {
                show(BipeStrings.appMediaSaveFailed)
            }
        }
    }

    /// Action save: to the phone gallery, asking the add-only permission the first time.
    private func save() {
        guard let media = current?.media else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard status == .authorized || status == .limited else {
                askGalleryPermission = true
                return
            }
            do {
                let url = try await loader.file(media.source, filename: media.filename, mimeType: media.mimeType)
                try await PHPhotoLibrary.shared().performChanges {
                    if media.kind == .video {
                        PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                    } else {
                        PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url)
                    }
                }
                show(BipeStrings.appMediaSaved)
            } catch {
                show(BipeStrings.appMediaSaveFailed)
            }
        }
    }

    private func show(_ text: String) {
        withBipeAnimation { notice = text }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withBipeAnimation { if notice == text { notice = nil } }
        }
    }
}

/// One photo or video of the viewer.
private struct MediaPage: View {
    let media: MediaMessage
    let loader: MediaLoader

    @State private var preview: UIImage?
    @State private var full: UIImage?
    @State private var player: AVPlayer?
    /// isLoadingFull of spec media-viewer.
    @State private var isLoadingFull = true
    @State private var failed = false

    var body: some View {
        ZStack {
            if let player {
                VideoPlayer(player: player)
                    .onDisappear { player.pause() }
            } else if let image = full ?? preview {
                ZoomableImage(image: image)
                    .accessibilityElement()
                    .accessibilityLabel(media.accessibilityLabel)
                    .accessibilityAddTraits(.isImage)
            }
            if isLoadingFull {
                ProgressView()
            } else if failed {
                Text(BipeStrings.appMediaFailed)
                    .font(.subheadline)
                    .padding(12)
                    .mediaScrim(RoundedRectangle(cornerRadius: 10))
                    .onTapGesture { Task { await load() } }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityHint(BipeStrings.appRetry)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await load() }
    }

    private func load() async {
        failed = false
        isLoadingFull = true
        defer { isLoadingFull = false }
        preview = await loader.previewImage(media, maxPixel: 900)
        if media.kind == .video {
            guard let url = try? await loader.file(media.source, filename: media.filename, mimeType: media.mimeType) else {
                failed = true
                return
            }
            // Sound on (spec media-viewer notes).
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            let player = AVPlayer(url: url)
            self.player = player
            player.play()
        } else {
            full = await loader.fullImage(media)
            failed = full == nil
        }
    }
}

/// Pinch and double tap zoom for a photo.
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.delegate = context.coordinator
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.backgroundColor = .clear
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = true
        imageView.frame = scrollView.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scrollView.addSubview(imageView)
        context.coordinator.imageView = imageView
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
        return scrollView
    }

    func updateUIView(_ scrollView: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            imageView
        }

        @objc func doubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView = recognizer.view as? UIScrollView else { return }
            scrollView.setZoomScale(scrollView.zoomScale > 1 ? 1 : 2.5, animated: true)
        }
    }
}
