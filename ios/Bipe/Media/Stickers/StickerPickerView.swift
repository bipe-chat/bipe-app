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
    /// State query: the search text. Search runs on the phone only (StickerSearch).
    @Published var query = ""
    @Published private(set) var tagsFailed = false
    @Published private(set) var phase = Phase.loading
    @Published private(set) var sendFailed = false
    @Published private(set) var isClosed = false

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
    }

    /// State favourites: the favourites that are in a loaded pack.
    var favouriteStickers: [Sticker] { StickerShortlist.resolve(favourites, in: packs) }

    /// State recents: newest first, without the favourites.
    var recentStickers: [Sticker] {
        StickerShortlist.resolve(recents.filter { !favourites.contains($0) }, in: packs)
    }

    func isFavourite(_ sticker: Sticker) -> Bool { favourites.contains(StickerRef(sticker)) }

    var isSearching: Bool { !StickerSearch.fold(query).isEmpty || !StickerSearch.emojiKey(query).isEmpty }

    /// State results: favourites first, then recents, then pack order, without duplicates.
    func results(packTitle: (StickerPack) -> String) -> [Sticker] {
        let titles = Dictionary(packs.map { ($0.id, packTitle($0)) }, uniquingKeysWith: { first, _ in first })
        return StickerSearch.results(query, in: [favouriteStickers, recentStickers] + packs.map(\.stickers)) { titles[$0] }
    }

    /// Action editTags: writes the tags into the user's pack, then reads the packs again without a spinner.
    func saveTags(_ sticker: Sticker, tags: [String]) async {
        tagsFailed = false
        guard let packClient else { return }
        do {
            try await packClient.setTags(shortcode: sticker.shortcode, tags: tags)
            if let loaded = try? await packClient.packs(roomId: roomId) { packs = loaded }
        } catch {
            tagsFailed = true
        }
    }

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
            try await room.sendRaw(eventType: "m.sticker", content: sticker.eventContentJSON())
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
    @State private var editingTags: Sticker?

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
                        if model.tagsFailed {
                            Text(BipeStrings.appSaveError)
                                .font(.footnote)
                                .foregroundStyle(BipeColor.trustDangerInk)
                        }
                        if model.sendFailed {
                            Text(BipeStrings.appStickerSendFailed)
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
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always), prompt: BipeStrings.stickersSearchPlaceholder)
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
        .sheet(item: $editingTags) { sticker in
            StickerTagsView(selected: Set(sticker.tags)) { tags in
                editingTags = nil
                if let tags { Task { await model.saveTags(sticker, tags: tags) } }
            }
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
        case .loaded where model.isSearching:
            let results = model.results(packTitle: title)
            if results.isEmpty {
                Text(BipeStrings.stickersSearchEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        grid(results)
                    }
                    .padding(16)
                }
            }
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
                        section(title(pack), pack.stickers)
                    }
                }
                .padding(16)
            }
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ stickers: [Sticker]) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(BipeColor.inkSecondary)
        grid(stickers)
    }

    private func grid(_ stickers: [Sticker]) -> some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(stickers) { sticker in
                Button {
                    Task { await model.send(sticker) }
                } label: {
                    StickerThumbnail(url: sticker.url, loader: loader)
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
                    if sticker.packId == "user" {
                        Button {
                            editingTags = sticker
                        } label: {
                            Label(BipeStrings.stickerEditTags, systemImage: "tag")
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

private struct StickerThumbnail: View {
    let url: String
    let loader: MediaLoader

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 10).fill(BipeColor.surfaceSunken)
            }
        }
        .task(id: url) { image = await loader.thumbnail(mxcUrl: url, size: 228) }
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

/// Action editTags of spec sticker-picker: the registry tags, several can be on. done(nil) cancels.
private struct StickerTagsView: View {
    @State var selected: Set<String>
    let done: ([String]?) -> Void

    var body: some View {
        NavigationStack {
            List(BipeRegistries.stickerTags, id: \.self) { tag in
                Toggle(stickerTagName(tag), isOn: Binding(
                    get: { selected.contains(tag) },
                    set: { on in if on { selected.insert(tag) } else { selected.remove(tag) } }))
            }
            .navigationTitle(BipeStrings.stickerEditTags)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { done(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.appSave) { done(BipeRegistries.stickerTags.filter(selected.contains)) }
                }
            }
        }
    }
}

/// The display name of a tag id of registries/stickers.json (string key bipe_sticker_tag_<id, - as _>).
func stickerTagName(_ tag: String) -> String {
    switch tag {
    case "riso": BipeStrings.stickerTagRiso
    case "amor": BipeStrings.stickerTagAmor
    case "bom-dia": BipeStrings.stickerTagBomDia
    case "boa-tarde": BipeStrings.stickerTagBoaTarde
    case "boa-noite": BipeStrings.stickerTagBoaNoite
    case "parabens": BipeStrings.stickerTagParabens
    case "obrigado": BipeStrings.stickerTagObrigado
    case "sextou": BipeStrings.stickerTagSextou
    case "tmj": BipeStrings.stickerTagTmj
    case "amem": BipeStrings.stickerTagAmem
    case "choro": BipeStrings.stickerTagChoro
    case "raiva": BipeStrings.stickerTagRaiva
    case "surpresa": BipeStrings.stickerTagSurpresa
    case "ok": BipeStrings.stickerTagOk
    case "tchau": BipeStrings.stickerTagTchau
    case "festa": BipeStrings.stickerTagFesta
    case "fome": BipeStrings.stickerTagFome
    case "cansado": BipeStrings.stickerTagCansado
    case "duvida": BipeStrings.stickerTagDuvida
    default: tag
    }
}
