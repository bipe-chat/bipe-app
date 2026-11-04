// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import QuickLook
import SwiftUI

/// Spec chat-media-gallery (bipe#672): the photos, videos and files of one chat, newest first.
@MainActor
final class ChatMediaGalleryModel: ObservableObject {
    enum Tab: Hashable {
        case media
        case files
    }

    /// Type GalleryItem.
    struct Item: Identifiable, Equatable {
        let id: String
        let media: MediaMessage
    }

    /// Action appear loads pages until a tab has this many items.
    static let firstFill = 30

    @Published var tab = Tab.media
    @Published private(set) var items: [Item] = []
    @Published private(set) var isLoading = true
    @Published private(set) var reachedStart = false

    var mediaItems: [Item] { items.filter { $0.media.kind != .file } }
    var fileItems: [Item] { items.filter { $0.media.kind == .file } }

    private let session: AccountSession
    private let roomId: String
    private var timeline: Timeline?
    private var handle: TaskHandle?
    private var timelineItems: [TimelineItem] = []
    private var paging = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    /// Action appear.
    func appear() async {
        guard timeline == nil, let room = try? session.client.getRoom(roomId: roomId) else {
            isLoading = false
            return
        }
        let configuration = TimelineConfiguration(focus: .live(hideThreadedEvents: false),
                                                  filter: .onlyMessage(types: [.image, .video, .file]),
                                                  internalIdPrefix: "gallery",
                                                  dateDividerMode: .daily,
                                                  trackReadReceipts: .disabled,
                                                  reportUtds: false)
        guard let timeline = try? await room.timelineWithConfiguration(configuration: configuration) else {
            isLoading = false
            return
        }
        self.timeline = timeline
        let stream = TimelineDiffStream()
        handle = await timeline.addListener(listener: stream)
        Task { [weak self] in
            guard let self else { return }
            while !reachedStart, max(mediaItems.count, fileItems.count) < Self.firstFill {
                guard await loadMore() else { break }
            }
            isLoading = false
        }
        for await diffs in stream.updates {
            for diff in diffs {
                timelineItems.apply(diff.vectorDiff)
            }
            rebuild()
        }
    }

    /// Action loadMore. false when the page failed.
    @discardableResult
    func loadMore() async -> Bool {
        guard let timeline, !reachedStart, !paging else { return true }
        paging = true
        defer { paging = false }
        guard let hitStart = try? await timeline.paginateBackwards(numEvents: 50) else { return false }
        reachedStart = hitStart
        return true
    }

    private func rebuild() {
        items = timelineItems.reversed().compactMap { item in
            guard let event = item.asEvent(), case .msgLike(let content) = event.content,
                  case .media(let media)? = MediaContent(content.kind), media.kind != .sticker else { return nil }
            return Item(id: item.uniqueId().id, media: media)
        }
    }
}

/// Spec chat-media-gallery.
struct ChatMediaGalleryView: View {
    @StateObject private var model: ChatMediaGalleryModel
    let media: MediaLoader
    @Environment(\.dismiss) private var dismiss
    @State private var viewing: String?
    @State private var fileURL: URL?
    @State private var openingFile = false
    @State private var fileFailed = false

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: ChatMediaGalleryModel(session: session, roomId: roomId))
        self.media = media
    }

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker(BipeStrings.galleryTitle, selection: $model.tab) {
                    Text(BipeStrings.galleryMedia).tag(ChatMediaGalleryModel.Tab.media)
                    Text(BipeStrings.galleryFiles).tag(ChatMediaGalleryModel.Tab.files)
                }
                .pickerStyle(.segmented)
                .padding(16)
                content
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.galleryTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.chatMediaGallery.rawValue)
        .task { await model.appear() }
        .fullScreenCover(item: Binding(get: { viewing.map(GalleryPage.init) }, set: { viewing = $0?.id })) { page in
            GalleryPager(items: model.mediaItems, loader: media, selection: page.id) { viewing = nil }
        }
        .quickLookPreview($fileURL)
        .alert(BipeStrings.appMediaFailed, isPresented: $fileFailed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
    }

    @ViewBuilder
    private var content: some View {
        let shown = model.tab == .media ? model.mediaItems : model.fileItems
        if shown.isEmpty {
            Spacer()
            if model.isLoading {
                ProgressView()
            } else {
                Text(model.tab == .media ? BipeStrings.galleryEmptyMedia : BipeStrings.galleryEmptyFiles)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                    .padding(24)
            }
            Spacer()
        } else if model.tab == .media {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(shown) { item in
                        Button { viewing = item.id } label: { GalleryTile(media: item.media, loader: media) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(item.media.accessibilityLabel)
                            .onAppear { if item.id == shown.last?.id { Task { await model.loadMore() } } }
                    }
                }
            }
        } else {
            List(shown) { item in
                Button { open(item.media) } label: {
                    HStack(spacing: 12) {
                        PhosphorIcon.file.image
                            .resizable()
                            .frame(width: 24, height: 24)
                            .foregroundStyle(BipeColor.glyphPrimary)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.media.filename)
                                .foregroundStyle(BipeColor.inkPrimary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            if let size = item.media.sizeLabel {
                                Text(size)
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                        }
                    }
                }
                .disabled(openingFile)
                .onAppear { if item.id == shown.last?.id { Task { await model.loadMore() } } }
            }
            .listStyle(.plain)
        }
    }

    /// Action open for a file: downloads it to the cache and opens the system preview.
    private func open(_ file: MediaMessage) {
        guard !openingFile else { return }
        openingFile = true
        Task {
            defer { openingFile = false }
            if let url = try? await media.file(file.source, filename: file.filename, mimeType: file.mimeType) {
                fileURL = url
            } else {
                fileFailed = true
            }
        }
    }
}

private struct GalleryPage: Identifiable {
    let id: String
}

/// One square tile of the grid; a video shows its duration.
private struct GalleryTile: View {
    let media: MediaMessage
    let loader: MediaLoader
    @State private var image: UIImage?

    var body: some View {
        BipeColor.surfaceSunken
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                if media.kind == .video {
                    HStack(spacing: 2) {
                        PhosphorIcon.play.image
                            .resizable()
                            .frame(width: 10, height: 10)
                            .accessibilityHidden(true)
                        if let duration = media.durationLabel {
                            Text(duration).font(.caption2.monospacedDigit())
                        }
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .mediaScrim(Capsule())
                    .padding(4)
                }
            }
            .task(id: media.source.url) { image = await loader.previewImage(media, maxPixel: 300) }
    }
}

/// Action open for media: a full screen pager over the gallery's media, with the pages of media-viewer.
private struct GalleryPager: View {
    let items: [ChatMediaGalleryModel.Item]
    let loader: MediaLoader
    @State var selection: String
    let close: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            BipeColor.surfaceBase.ignoresSafeArea()
            TabView(selection: $selection) {
                ForEach(items) { item in
                    MediaPage(media: item.media, loader: loader)
                        .tag(item.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            Button(action: close) {
                PhosphorIcon.x.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .padding(10)
                    .mediaScrim(Circle())
                    .bipeTapTarget()
            }
            .accessibilityLabel(BipeStrings.appClose)
            .padding(16)
        }
        .environment(\.colorScheme, .dark)
    }
}
