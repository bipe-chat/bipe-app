// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec sticker-pack: a pack from a pack link or from a received sticker, and adding it (ADR 0205, bipe-chat/bipe#842).
@MainActor
final class StickerPackModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case added
        case failed
    }

    @Published private(set) var phase = Phase.loading
    @Published private(set) var preview: StickerPackPreview?
    @Published private(set) var authorHandle: String?
    @Published private(set) var isAdding = false

    let ref: StickerPackRef
    private let client: Client
    private let packClient: StickerPackClient?
    private let handles: BipeHandleResolver?

    init(ref: StickerPackRef, client: Client, handles: BipeHandleResolver?) {
        self.ref = ref
        self.client = client
        self.handles = handles
        packClient = StickerPickerModel.packClient(client)
    }

    /// State title: the pack display name, else the room name.
    var title: String {
        preview?.pack.displayName ?? preview?.roomName ?? BipeStrings.stickersTitle
    }

    /// Action appear.
    func appear() async {
        phase = .loading
        guard let packClient, let loaded = try? await packClient.preview(ref) else {
            phase = .failed
            return
        }
        preview = loaded
        phase = (try? await packClient.isEnabled(ref)) == true ? .added : .loaded
        if let creator = loaded.creatorId {
            authorHandle = await handles?.resolve(creator)
        }
    }

    /// Action add: joins the public room, then adds it to im.ponies.emote_rooms.
    func add() async {
        guard let packClient, !isAdding else { return }
        isAdding = true
        defer { isAdding = false }
        do {
            if (try? client.getRoom(roomId: ref.roomId))?.membership() != .joined {
                _ = try await client.joinRoomById(roomId: ref.roomId)
            }
            try await packClient.enable(ref)
            phase = .added
        } catch {
            phase = .failed
        }
    }
}

struct StickerPackView: View {
    @StateObject var model: StickerPackModel
    let loader: MediaLoader
    let close: () -> Void

    private let columns = [GridItem(.adaptive(minimum: 76), spacing: 8)]

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(model.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appClose, action: close)
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.stickerPack.rawValue)
        .task { await model.appear() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.stickerPackFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await model.appear() } }
                    .foregroundStyle(BipeColor.accentInk)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded, .added:
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let handle = model.authorHandle {
                        Text(BipeStrings.stickerPackBy("@\(handle)"))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(model.preview?.pack.stickers ?? []) { sticker in
                            StickerPackCell(url: sticker.url, loader: loader)
                                .frame(width: 76, height: 76)
                                .accessibilityLabel(sticker.body)
                        }
                    }
                }
                .padding(16)
            }
            .safeAreaInset(edge: .bottom) {
                Group {
                    if model.phase == .added {
                        Text(BipeStrings.stickerPackAdded)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BipeColor.inkSecondary)
                    } else {
                        Button {
                            Task { await model.add() }
                        } label: {
                            Text(BipeStrings.stickerPackAdd).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bipeProminent)
                        .disabled(model.isAdding)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity)
                .background(BipeColor.surfaceRaised)
            }
        }
    }
}

/// One sticker of the pack: the server's still (sticker-pack shows the pack; the picker plays it).
private struct StickerPackCell: View {
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
