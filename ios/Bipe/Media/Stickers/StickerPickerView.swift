// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import PhotosUI
import SwiftUI

/// Spec sticker-picker: favourites, recents, the user's pack, this chat's packs and the enabled room packs
/// (ADR 0029, ADR 0033, ADR 0205).
@MainActor
final class StickerPickerModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed
    }

    @Published private(set) var packs: [StickerPack] = []
    @Published private(set) var favourites: [StickerRef] = []
    @Published private(set) var recents: [StickerRef] = []
    @Published private(set) var phase = Phase.loading
    @Published private(set) var sendFailed = false
    @Published private(set) var isClosed = false
    /// StickerPackView.canShare: the user's pack, and room packs the user may write.
    @Published private(set) var shareablePackIds: Set<String> = []
    @Published private(set) var isSharing = false
    @Published private(set) var shareFailed = false
    /// The link for the system share sheet (action sharePack).
    @Published var shareLink: URL?

    let roomId: String
    let client: Client
    let packClient: StickerPackClient?

    init(client: Client, roomId: String) {
        self.client = client
        self.roomId = roomId
        packClient = Self.packClient(client)
    }

    static func packClient(_ client: Client) -> StickerPackClient? {
        guard let session = try? client.session(), let url = URL(string: session.homeserverUrl) else { return nil }
        return StickerPackClient(homeserverURL: url, userId: session.userId) { try? client.session().accessToken }
    }

    /// Action appear: loads the packs.
    func appear() async {
        phase = .loading
        guard let packClient, let loaded = try? await packClient.packs(roomId: roomId) else {
            phase = .failed
            return
        }
        packs = loaded
        phase = .loaded
        // The lists are extras: a failed read shows the packs without them.
        favourites = (try? await packClient.shortlist(BipeRegistries.stickerFavouritesEventType)) ?? favourites
        recents = (try? await packClient.shortlist(BipeRegistries.stickerRecentsEventType)) ?? recents
        var shareable = Set(loaded.filter { $0.source == .user }.map(\.id))
        for pack in loaded {
            if let ref = pack.ref, await packClient.canWritePack(roomId: ref.roomId) { shareable.insert(pack.id) }
        }
        shareablePackIds = shareable
    }

    /// Action sharePack: the user's pack becomes a pack room with a copy of it; a room pack shares its own room.
    func share(_ pack: StickerPack) async {
        guard let packClient, !isSharing else { return }
        isSharing = true
        shareFailed = false
        defer { isSharing = false }
        do {
            let ref: StickerPackRef
            if let roomRef = pack.ref {
                ref = roomRef
            } else {
                ref = try await packClient.createPackRoom(displayName: BipeStrings.stickersPackPersonal)
            }
            shareLink = URL(string: ref.link)
        } catch {
            shareFailed = true
        }
    }

    /// State favourites: the favourites that are in a loaded pack.
    var favouriteStickers: [Sticker] { StickerShortlist.resolve(favourites, in: packs) }

    /// State recents: newest first, without the favourites.
    var recentStickers: [Sticker] {
        StickerShortlist.resolve(recents.filter { !favourites.contains($0) }, in: packs)
    }

    func isFavourite(_ sticker: Sticker) -> Bool { favourites.contains(StickerRef(sticker)) }

    /// Action toggleFavourite. The whole list is written, so a failed write is carried by the next change.
    func toggleFavourite(_ sticker: Sticker) {
        favourites = StickerShortlist.toggleFavourite(StickerRef(sticker), in: favourites)
        let list = favourites
        Task { [packClient] in try? await packClient?.setShortlist(BipeRegistries.stickerFavouritesEventType, list) }
    }

    /// Action send: an m.sticker event through Room.sendRaw, which encrypts in an encrypted room. Then closes.
    func send(_ sticker: Sticker) async {
        sendFailed = false
        do {
            guard let room = try client.getRoom(roomId: roomId) else { throw CocoaError(.featureUnsupported) }
            try await room.sendRaw(eventType: "m.sticker", content: sticker.sendContentJSON())
            recents = StickerShortlist.pushRecent(StickerRef(sticker), into: recents)
            let list = recents
            Task { [packClient] in try? await packClient?.setShortlist(BipeRegistries.stickerRecentsEventType, list) }
            isClosed = true
        } catch {
            sendFailed = true
        }
    }
}

struct StickerPickerView: View {
    @StateObject var model: StickerPickerModel
    let loader: MediaLoader
    let close: () -> Void

    @State private var showsImport = false
    @State private var showsFromPhoto = false

    private let columns = [GridItem(.adaptive(minimum: 76), spacing: 8)]

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(BipeStrings.stickersTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appClose, action: close)
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 4) {
                        if model.sendFailed {
                            Text(BipeStrings.appStickerSendFailed)
                                .font(.footnote)
                                .foregroundStyle(BipeColor.trustDangerInk)
                        }
                        if model.shareFailed {
                            Text(BipeStrings.stickersPackShareFailed)
                                .font(.footnote)
                                .foregroundStyle(BipeColor.trustDangerInk)
                        }
                        HStack(spacing: 12) {
                            Button(BipeStrings.stickersFromPhoto) { showsFromPhoto = true }
                            Button(BipeStrings.stickersImportAction) { showsImport = true }
                        }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(BipeColor.accentInk)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity)
                    .background(BipeColor.surfaceRaised)
                }
        }
        .accessibilityIdentifier(BipeScreen.stickerPicker.rawValue)
        .task { await model.appear() }
        .onChange(of: model.isClosed) { _, closed in
            if closed { close() }
        }
        .sheet(isPresented: $showsImport, onDismiss: { Task { await model.appear() } }) {
            StickerImportView(packClient: model.packClient) { showsImport = false }
        }
        .sheet(isPresented: $showsFromPhoto, onDismiss: { Task { await model.appear() } }) {
            StickerFromPhotoView(packClient: model.packClient) { showsFromPhoto = false }
        }
        .sheet(item: Binding(get: { model.shareLink.map(SharedPackLink.init) }, set: { model.shareLink = $0?.url })) { link in
            ShareSheet(items: [link.url])
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.stickersErrorLoad)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.appRetry) { Task { await model.appear() } }
                    .foregroundStyle(BipeColor.accentInk)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded where model.packs.isEmpty:
            VStack(spacing: 8) {
                Text(BipeStrings.stickersEmptyTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.stickersEmptyBody)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    let favourites = model.favouriteStickers
                    if !favourites.isEmpty {
                        section(BipeStrings.stickersFavourites, favourites)
                    }
                    let recents = model.recentStickers
                    if !recents.isEmpty {
                        section(BipeStrings.stickersRecents, recents)
                    }
                    ForEach(model.packs) { pack in
                        section(title(pack), pack.stickers, share: model.shareablePackIds.contains(pack.id) ? { Task { await model.share(pack) } } : nil)
                    }
                }
                .padding(16)
            }
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ stickers: [Sticker], share: (() -> Void)? = nil) -> some View {
        HStack {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BipeColor.inkSecondary)
            Spacer()
            if let share {
                Button(BipeStrings.stickersPackShare, action: share)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(BipeColor.accentInk)
                    .disabled(model.isSharing)
            }
        }
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(stickers) { sticker in
                Button {
                    Task { await model.send(sticker) }
                } label: {
                    StickerThumbnail(url: sticker.url, mayBeAnimated: sticker.mayBeAnimated, loader: loader)
                        .frame(width: 76, height: 76)
                }
                .accessibilityLabel(BipeStrings.a11yStickerSend(sticker.body))
                .contextMenu {
                    // Long press (spec sticker-picker toggleFavourite).
                    Button {
                        model.toggleFavourite(sticker)
                    } label: {
                        if model.isFavourite(sticker) {
                            Label(BipeStrings.stickerFavouriteRemove, systemImage: "star.slash")
                        } else {
                            Label(BipeStrings.stickerFavouriteAdd, systemImage: "star")
                        }
                    }
                }
            }
        }
    }

    private func title(_ pack: StickerPack) -> String {
        switch pack.source {
        case .user: BipeStrings.stickersPackPersonal
        case .room(let roomId, _) where roomId == model.roomId: BipeStrings.stickersPackRoom
        case .room: pack.displayName ?? BipeStrings.stickersPackRoom
        }
    }
}

/// The link of action sharePack, for the share sheet.
private struct SharedPackLink: Identifiable {
    let url: URL
    var id: URL { url }
}

/// One sticker of the picker. An animated WebP plays in a loop, except with the data saver on mobile data or
/// Reduce Motion: then its first frame shows (spec sticker-picker notes).
private struct StickerThumbnail: View {
    let url: String
    let mayBeAnimated: Bool
    let loader: MediaLoader

    @State private var image: UIImage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var plays: Bool { !reduceMotion && !DataUseMeter.shared.savesData }

    var body: some View {
        ZStack {
            if let image, image.isAnimatedImage, plays {
                AnimatedImageView(image: image)
            } else if let image {
                Image(uiImage: image.firstFrame).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 10).fill(BipeColor.surfaceSunken)
            }
        }
        .task(id: "\(url)#\(plays)") {
            // Only a sticker that plays needs its file; the server's still is smaller.
            if mayBeAnimated, plays, let animated = await loader.stickerImage(mxcUrl: url, maxPixel: 228) {
                image = animated
            } else if image == nil {
                image = await loader.thumbnail(mxcUrl: url, size: 228)
            }
        }
    }
}

extension Sticker {
    /// An animated WebP as far as the pack says: is_animated, or a WebP whose info does not say (ADR 0205).
    var mayBeAnimated: Bool {
        isAnimated ?? (mimeType == nil || mimeType == "image/webp")
    }
}

/// Spec sticker-import: WhatsApp export (ZIP) or WebP and PNG files into the user's pack.
struct StickerImportView: View {
    enum Phase: Equatable {
        case intro
        case importing
        case done(Int)
        case nothingNew
        case failed
    }

    let packClient: StickerPackClient?
    let close: () -> Void

    @State private var phase = Phase.intro
    @State private var choosing = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                switch phase {
                case .intro:
                    Text(BipeStrings.stickersImportHelp)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.stickersImportNote)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Button(BipeStrings.stickersImportAction) { choosing = true }
                        .buttonStyle(.bipeProminent)
                        .tint(BipeColor.accentFill)
                case .importing:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(BipeStrings.stickersImportProgress)
                            .foregroundStyle(BipeColor.inkPrimary)
                    }
                case .done(let added):
                    Text(BipeStrings.stickersImportDone(String(added)))
                        .foregroundStyle(BipeColor.inkPrimary)
                case .nothingNew:
                    Text(BipeStrings.stickersImportNone)
                        .foregroundStyle(BipeColor.inkPrimary)
                case .failed:
                    Text(BipeStrings.stickersImportFailed)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Spacer()
            }
            .padding(20)
            .navigationTitle(BipeStrings.stickersImportAction)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose, action: close)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.stickerImport.rawValue)
        .fileImporter(isPresented: $choosing,
                      allowedContentTypes: [.zip, .webP, .png],
                      allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            Task { await run(urls) }
        }
    }

    /// Action chooseFiles: reads the candidates and runs the import.
    private func run(_ urls: [URL]) async {
        phase = .importing
        let candidates = await Task.detached { () -> [StickerImportCandidate] in
            urls.flatMap { url -> [StickerImportCandidate] in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url) else { return [] }
                return StickerImporter.candidates(fileName: url.lastPathComponent, data: data)
            }
        }.value
        guard !candidates.isEmpty else {
            phase = .nothingNew
            return
        }
        do {
            guard let packClient else { throw CocoaError(.featureUnsupported) }
            let added = try await packClient.addToUserPack(candidates)
            phase = added > 0 ? .done(added) : .nothingNew
        } catch {
            phase = .failed
        }
    }
}

/// Spec sticker-from-photo: a photo on the device becomes a 512 by 512 sticker in the user's pack.
struct StickerFromPhotoView: View {
    enum Phase: Equatable {
        case picking
        case making
        case preview
        case saving
        case failed(String)
    }

    let packClient: StickerPackClient?
    let close: () -> Void

    @State private var phase = Phase.picking
    @State private var item: PhotosPickerItem?
    @State private var candidate: StickerImportCandidate?
    @State private var saved = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                switch phase {
                case .picking:
                    PhotosPicker(selection: $item, matching: .images) {
                        Text(BipeStrings.stickersFromPhoto)
                    }
                    .buttonStyle(.bipeProminent)
                    .tint(BipeColor.accentFill)
                case .making:
                    ProgressView()
                    Text(BipeStrings.appStickerMaking)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .preview, .saving:
                    if let candidate, let image = UIImage(data: candidate.data) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .background(CheckerBoard().clipShape(RoundedRectangle(cornerRadius: 16)))
                    }
                    if saved {
                        Text(BipeStrings.stickerSaved)
                            .foregroundStyle(BipeColor.inkSecondary)
                    } else {
                        Button {
                            Task { await save() }
                        } label: {
                            if phase == .saving { ProgressView() } else { Text(BipeStrings.actionSaveSticker) }
                        }
                        .buttonStyle(.bipeProminent)
                        .tint(BipeColor.accentFill)
                        .disabled(phase == .saving)
                    }
                case .failed(let text):
                    Text(text)
                        .foregroundStyle(BipeColor.trustDangerInk)
                        .multilineTextAlignment(.center)
                    PhotosPicker(selection: $item, matching: .images) {
                        Text(BipeStrings.stickersFromPhoto)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(BipeStrings.stickersFromPhoto)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel, action: close)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.stickerFromPhoto.rawValue)
        .onChange(of: item) { _, item in
            guard let item else { return }
            Task { await make(item) }
        }
    }

    /// Action pickPhoto: then phase making, on the device only.
    private func make(_ item: PhotosPickerItem) async {
        phase = .making
        saved = false
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            phase = .failed(BipeStrings.appStickerMakeFailed)
            return
        }
        let made = await Task.detached { StickerMaker.sticker(fromImageData: data) }.value
        if let made {
            candidate = made
            phase = .preview
        } else {
            phase = .failed(BipeStrings.appStickerMakeFailed)
        }
    }

    /// Action save: adds the sticker to the user's pack, then back to sticker-picker.
    private func save() async {
        guard let candidate else { return }
        phase = .saving
        do {
            guard let packClient else { throw CocoaError(.featureUnsupported) }
            _ = try await packClient.addToUserPack([candidate])
            saved = true
            phase = .preview
            try? await Task.sleep(for: .seconds(1))
            close()
        } catch {
            phase = .failed(BipeStrings.stickerSaveFailed)
        }
    }
}

/// The checked background behind a sticker preview, so transparency shows.
private struct CheckerBoard: View {
    var body: some View {
        Canvas { context, size in
            let cell: CGFloat = 12
            for row in 0..<Int(size.height / cell) + 1 {
                for column in 0..<Int(size.width / cell) + 1 where (row + column).isMultiple(of: 2) {
                    context.fill(Path(CGRect(x: CGFloat(column) * cell, y: CGFloat(row) * cell, width: cell, height: cell)),
                                 with: .color(BipeColor.surfaceSunken))
                }
            }
        }
        .background(BipeColor.surfaceRaised)
    }
}
