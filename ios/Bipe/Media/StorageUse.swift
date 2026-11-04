// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Spec data-use, storage (bipe-chat/bipe#826): the space Bipe uses on this phone. Sizes in bytes.
struct StorageUse: Equatable {
    /// mediaCacheBytes: the SDK media store and the app's media files that are not voice.
    var mediaCacheBytes: Int64
    /// voiceBytes: the voice files in the app's media folders.
    var voiceBytes: Int64
    /// storeBytes: messages, chat state and keys (state, event cache and crypto stores).
    var storeBytes: Int64

    var total: Int64 { mediaCacheBytes + voiceBytes + storeBytes }
}

/// Spec data-use notes: measures the SDK stores and the app's media folders, and deletes the folders that
/// clearMediaCache deletes.
enum StorageMeter {
    /// A file with one of these extensions counts as voice.
    static let voiceExtensions: Set<String> = ["ogg", "oga", "opus", "m4a", "aac", "mp3", "wav", "caf"]

    private static var caches: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    }

    private static var voiceFolder: URL { caches.appendingPathComponent("Voice", isDirectory: true) }

    /// Every folder that counts (spec data-use notes).
    private static var measuredFolders: [URL] {
        var folders = [voiceFolder,
                       caches.appendingPathComponent("Uploads", isDirectory: true),
                       FileManager.default.temporaryDirectory]
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup) {
            folders.append(group.appendingPathComponent("SharePending", isDirectory: true))
            folders.append(group.appendingPathComponent("PixPending", isDirectory: true))
        }
        return folders
    }

    /// Action measureStorage, off the main thread.
    static func measure(client: Client) async -> StorageUse {
        let sizes = try? await client.getStoreSizes()
        let files = await Task.detached(priority: .utility) { Self.folderBytes(Self.measuredFolders) }.value
        let mediaStore = Int64(clamping: sizes?.mediaStore ?? 0)
        let stores = [sizes?.stateStore, sizes?.eventCacheStore, sizes?.cryptoStore]
            .reduce(Int64(0)) { $0 + Int64(clamping: $1 ?? 0) }
        return StorageUse(mediaCacheBytes: mediaStore + files.media, voiceBytes: files.voice, storeBytes: stores)
    }

    /// clearMediaCache: Caches/Voice and the contents of tmp. Uploads and the share and Pix handoffs stay: a send or a
    /// share may still need them.
    static func deleteAppMedia() async {
        await Task.detached(priority: .utility) {
            let manager = FileManager.default
            try? manager.removeItem(at: Self.voiceFolder)
            let tmp = manager.temporaryDirectory
            for item in (try? manager.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? [] {
                try? manager.removeItem(at: item)
            }
        }.value
    }

    private static func folderBytes(_ folders: [URL]) -> (media: Int64, voice: Int64) {
        var media: Int64 = 0
        var voice: Int64 = 0
        let keys: [URLResourceKey] = [.isRegularFileKey, .totalFileAllocatedSizeKey, .fileSizeKey]
        for folder in folders {
            guard let walker = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys) else { continue }
            for case let url as URL in walker {
                guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
                let bytes = Int64(values.totalFileAllocatedSize ?? values.fileSize ?? 0)
                if Self.voiceExtensions.contains(url.pathExtension.lowercased()) {
                    voice += bytes
                } else {
                    media += bytes
                }
            }
        }
        return (media, voice)
    }
}
