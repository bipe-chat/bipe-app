// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Combine
import CoreServices
import ImageIO
import MatrixRustSDK
import UniformTypeIdentifiers

struct MacPhoto {
    let content: ImageMessageContent
    let description: String?
}

/// One loader per account. The SDK authenticates and decrypts media; decrypted images stay in memory.
@MainActor
final class MacMedia: ObservableObject {
    @Published private(set) var policy: MediaPreviews? = nil
    private var client: Client?
    private var task: Task<Void, Never>?
    private var handle: TaskHandle?
    private let images = NSCache<NSString, NSImage>()
    private let accountTypes = NSCache<NSString, NSNumber>()
    private let api = BipeAPIClient(baseURL: URL(string: "https://api.bipe.net.br")!)
    private var generation = UUID()
    /// Spec timeline-media fromStranger. The Mac has no chat-requests yet, so it reads the account's list.
    @Published private var requestRooms: Set<String> = []
    @Published private var requestRoomsChecked: Set<String> = []

    init() { images.totalCostLimit = 64 * 1024 * 1024 }

    func attach(_ client: Client) {
        reset()
        self.client = client
        let generation = generation
        task = Task { [weak self] in
            let stream = MacMediaPreviewStream()
            let handle = try? await client.subscribeToMediaPreviewConfig(listener: stream)
            guard let self, self.generation == generation else { handle?.cancel(); return }
            self.handle = handle
            do {
                let config = try await client.fetchMediaPreviewConfig()
                guard !Task.isCancelled, self.generation == generation else { return }
                self.policy = config?.mediaPreviews ?? .on
            } catch {
                // Offline: wait for the account setting through sync before fetching someone else's images.
            }
            // The SDK sends its stored value first, which is nil before the first sync. Only a real value changes the
            // policy: a nil must not replace a fetched setting or end the wait after a failed fetch.
            for await config in stream.updates {
                guard !Task.isCancelled, self.generation == generation else { return }
                if let previews = config?.mediaPreviews { self.policy = previews }
            }
        }
    }

    func reset() {
        generation = UUID()
        task?.cancel(); task = nil
        handle?.cancel(); handle = nil
        client = nil
        policy = nil
        images.removeAllObjects()
        accountTypes.removeAllObjects()
        requestRooms = []
        requestRoomsChecked = []
    }

    /// A room waits for a tap until a read of the account's list succeeds for it. The Mac has no address book, so
    /// nobody counts as a contact.
    func isFromRequest(_ roomID: String) -> Bool {
        !requestRoomsChecked.contains(roomID) || requestRooms.contains(roomID)
    }

    func checkRequestRooms(for roomID: String) async {
        guard let client else { return }
        let generation = generation
        do {
            let json = try await ServerAccountData.json(client: client, eventType: BipeRequestRooms.eventType)
            guard self.generation == generation else { return }
            requestRooms = Set(BipeRequestRooms.parse(json))
            requestRoomsChecked.insert(roomID)
        } catch {
            // The room keeps waiting for a tap. Opening it again reads the list again.
        }
    }

    func maxUploadSize() async -> UInt64? { try? await client?.getMaxMediaUploadSize() }

    func avatar(url: String?, userID: String?, size: Int) async -> NSImage? {
        guard let client else { return nil }
        let generation = generation
        var url = url
        if url == nil, let userID { url = try? await client.getProfile(userId: userID).avatarUrl }
        guard let url, let source = try? MediaSource.fromUrl(url: url) else { return nil }
        let key = "avatar|\(url)|\(size)" as NSString
        if let cached = images.object(forKey: key) { return cached }
        guard let data = try? await client.getMediaThumbnail(mediaSource: source, width: UInt64(size), height: UInt64(size)),
              let cgImage = try? MacImagePreparation.decode(data, maxPixel: size),
              !Task.isCancelled, self.generation == generation else { return nil }
        let image = NSImage(cgImage: cgImage, size: .zero)
        images.setObject(image, forKey: key, cost: cgImage.bytesPerRow * cgImage.height)
        return image
    }

    func isCompany(_ userID: String) async -> Bool {
        if let cached = accountTypes.object(forKey: userID as NSString) { return cached.boolValue }
        let generation = generation
        guard let type = try? await api.accountType(userID: userID), self.generation == generation else { return false }
        let company = type == .company
        accountTypes.setObject(NSNumber(value: company), forKey: userID as NSString)
        return company
    }

    func showsAutomatically(isOwn: Bool, isPrivate: Bool, fromRequest: Bool) -> Bool {
        if isOwn { return true }
        if fromRequest { return false }
        switch policy {
        case .on: return true
        case .private: return isPrivate
        default: return false
        }
    }

    func image(_ photo: MacPhoto, eventID: String, fullSize: Bool = false) async throws -> NSImage {
        guard let client else { throw CancellationError() }
        let generation = generation
        let key = (eventID + "|" + photo.content.source.url() + (fullSize ? "|full" : "|preview")) as NSString
        if let cached = images.object(forKey: key) { return cached }
        let source = fullSize ? photo.content.source : photo.content.info?.thumbnailSource ?? photo.content.source
        let data = try await client.getMediaContent(mediaSource: source)
        try Task.checkCancellation()
        guard self.generation == generation else { throw CancellationError() }
        if source === photo.content.source,
           data.count <= 16 * 1024 * 1024,
           let source = CGImageSourceCreateWithData(data as CFData, nil),
           CGImageSourceGetCount(source) > 1,
           let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let width = properties[kCGImagePropertyPixelWidth] as? Int,
           let height = properties[kCGImagePropertyPixelHeight] as? Int,
           width > 0, height > 0, width <= 4096, height <= 4096,
           Double(width) * Double(height) * Double(CGImageSourceGetCount(source)) <= 64_000_000,
           let animation = NSImage(data: data) {
            images.setObject(animation, forKey: key, cost: width * height * 4 * CGImageSourceGetCount(source))
            return animation
        }
        let side = fullSize ? 4096 : 1000
        let cgImage = try await Task.detached(priority: .userInitiated) {
            try MacImagePreparation.decode(data, maxPixel: side)
        }.value
        try Task.checkCancellation()
        guard self.generation == generation else { throw CancellationError() }
        let image = NSImage(cgImage: cgImage, size: .zero)
        images.setObject(image, forKey: key, cost: cgImage.bytesPerRow * cgImage.height)
        return image
    }

    func save(_ photo: MacPhoto) async throws {
        guard let client else { throw CancellationError() }
        let generation = generation
        let data = try await client.getMediaContent(mediaSource: photo.content.source)
        guard self.generation == generation else { throw CancellationError() }
        let saved = try MacSavedImage(data: data, filename: photo.content.filename)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = saved.filename
        panel.allowedContentTypes = [saved.type]
        panel.allowsOtherFileTypes = false
        guard await panel.begin() == .OK, let url = panel.url else { return }
        guard self.generation == generation else { throw CancellationError() }
        try data.write(to: url, options: .atomic)
        do { try MacSavedImage.quarantine(url) } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }
}

/// The sender chooses the filename and the bytes of an image event. A saved file gets the type its bytes prove,
/// and the same quarantine as a downloaded file.
struct MacSavedImage {
    private static let types: [UTType] = [.jpeg, .png, .gif, .heic, .heif, .webP, .tiff, .bmp]
    let filename: String
    let type: UTType

    init(data: Data, filename: String) throws {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0,
              CGImageSourceCopyPropertiesAtIndex(source, 0, nil) != nil,
              let identifier = CGImageSourceGetType(source),
              let type = UTType(identifier as String), Self.types.contains(type),
              let suffix = type.preferredFilenameExtension else { throw CocoaError(.fileReadCorruptFile) }
        self.type = type
        self.filename = Self.stem(filename) + "." + suffix
    }

    private static func stem(_ filename: String) -> String {
        let last = filename.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        // Control and format characters include the bidi overrides that can hide an extension.
        let scalars = (last as NSString).deletingPathExtension.unicodeScalars.filter {
            $0.properties.generalCategory != .control && $0.properties.generalCategory != .format
        }
        let stem = String(String.UnicodeScalarView(scalars)).trimmingCharacters(in: CharacterSet(charactersIn: ". ").union(.whitespacesAndNewlines))
        return stem.isEmpty ? BipeStrings.appMediaImage : stem
    }

    static func quarantine(_ url: URL) throws {
        var saved = url
        var values = URLResourceValues()
        values.quarantineProperties = [kLSQuarantineTypeKey as String: kLSQuarantineTypeInstantMessageAttachment as String,
                                       kLSQuarantineAgentNameKey as String: "Bipe"]
        try saved.setResourceValues(values)
    }
}

private final class MacMediaPreviewStream: MediaPreviewConfigListener {
    let updates: AsyncStream<MediaPreviewConfig?>
    private let continuation: AsyncStream<MediaPreviewConfig?>.Continuation
    init() { (updates, continuation) = AsyncStream.makeStream() }
    func onChange(mediaPreviewConfig: MediaPreviewConfig?) { continuation.yield(mediaPreviewConfig) }
    deinit { continuation.finish() }
}
