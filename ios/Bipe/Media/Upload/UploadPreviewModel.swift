// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UniformTypeIdentifiers

/// Type PickedMedia of spec upload-preview.
struct PickedMedia: Identifiable, Equatable {
    enum Kind: Equatable {
        case image
        case video
        case file
    }

    let id = UUID()
    var kind: Kind
    var filename: String
    var sizeLabel: String
    var isTooLarge: Bool
    /// The picked file, copied into the app cache.
    var localURL: URL
    var size: UInt64
    /// image only: the sender's description for screen readers (bipe-chat/bipe#501).
    var description = ""

    /// The kind from the file type: images and videos go as such, everything else as a file.
    static func kind(of url: URL) -> Kind {
        guard let type = UTType(filenameExtension: url.pathExtension) else { return .file }
        if type.conforms(to: .image) { return .image }
        if type.conforms(to: .movie) || type.conforms(to: .video) { return .video }
        return .file
    }
}

/// Spec upload-preview (attachment-preview of the composer spec is the same screen).
@MainActor
final class UploadPreviewModel: ObservableObject {
    /// At most 10 items; each is sent as its own message.
    static let maxItems = 10

    @Published private(set) var items: [PickedMedia] = []
    @Published var caption = ""
    @Published private(set) var isPreparing = false
    @Published private(set) var maxUploadLabel: String?
    @Published private(set) var isClosed = false
    /// true once send handed the items to the send queue (spec share-to-chat opens the chat then).
    @Published private(set) var didSend = false

    let quality = MediaQuality.current
    let replyToEventId: String?
    private let client: Client
    private let timeline: Timeline

    /// caption: the composer has none; share-to-chat passes its optional message.
    init(urls: [URL], client: Client, timeline: Timeline, replyToEventId: String?, caption: String = "") {
        self.client = client
        self.timeline = timeline
        self.replyToEventId = replyToEventId
        items = urls.prefix(Self.maxItems).map { url in
            let size = MediaPreparation.fileSize(url)
            return PickedMedia(kind: PickedMedia.kind(of: url),
                               filename: url.lastPathComponent,
                               sizeLabel: MediaFormat.size(size),
                               isTooLarge: false,
                               localURL: url,
                               size: size)
        }
        self.caption = caption
    }

    var canSend: Bool {
        !items.isEmpty && !isPreparing && !items.contains(where: \.isTooLarge)
    }

    /// Reads the homeserver upload limit and marks items over it.
    func appear() async {
        guard let limit = try? await client.getMaxMediaUploadSize(), limit > 0 else { return }
        maxUploadLabel = MediaFormat.size(limit)
        for index in items.indices {
            items[index].isTooLarge = items[index].size > limit
        }
    }

    /// Action editCaption.
    func editCaption(_ text: String) {
        caption = text
    }

    /// Action editDescription: the description of one image item, cut at the registry limit.
    func editDescription(index: Int, text: String) {
        guard items.indices.contains(index), items[index].kind == .image else { return }
        items[index].description = String(text.prefix(BipeRegistries.imageDescriptionMaxLength))
    }

    /// Action remove: the screen closes when none is left.
    func remove(index: Int) {
        guard items.indices.contains(index) else { return }
        items.remove(at: index)
        if items.isEmpty { isClosed = true }
    }

    /// Action cancel: closes without sending; the composer draft stays.
    func cancel() {
        isClosed = true
    }

    /// Action send: each item as an attachment with its thumbnail and info, the caption on the first
    /// item, an image's description in its content (bipe-chat/bipe#501), as a reply when replyToEventId is set,
    /// then closes. The send queue keeps the uploads.
    func send() async {
        guard canSend else { return }
        isPreparing = true
        let caption = caption.nilIfBlank
        var prepared: [(media: PreparedMedia, description: String?)] = []
        for item in items {
            if let media = try? await MediaPreparation.prepare(item.localURL, kind: item.kind, quality: quality) {
                prepared.append((media, item.kind == .image ? item.description : nil))
            }
        }
        isPreparing = false
        for (index, (media, description)) in prepared.enumerated() {
            let params = UploadParameters(source: .file(filename: media.file.path),
                                          caption: index == 0 ? caption : nil,
                                          formattedCaption: nil,
                                          mentions: nil,
                                          inReplyTo: index == 0 ? replyToEventId : nil,
                                          extraContentJson: BipeImageDescription.extraContentJson(description))
            let thumbnail = media.thumbnail.map { UploadSource.file(filename: $0.path) }
            // Spec data-use: the upload counts on the network in use now.
            DataUseMeter.shared.recordSent(Int64(MediaPreparation.fileSize(media.file) + (media.thumbnail.map(MediaPreparation.fileSize) ?? 0)))
            switch media.info {
            case .image(let info):
                _ = try? timeline.sendImage(params: params, thumbnailSource: thumbnail, imageInfo: info)
            case .video(let info):
                _ = try? timeline.sendVideo(params: params, thumbnailSource: thumbnail, videoInfo: info)
            case .file(let info):
                _ = try? timeline.sendFile(params: params, fileInfo: info)
            }
        }
        didSend = true
        isClosed = true
    }
}
