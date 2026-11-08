// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The encrypted SDK stores hold what the Mac keeps. Nothing else stays on disk unencrypted: no URL cache, which
/// would list the people the app looked up, and no prepared attachments or decrypted previews after a quit or a crash.
enum MacLocalCache {
    /// Call at launch and normal quit. Launch also removes files left by a crash.
    static func clean() {
        URLCache.shared = URLCache(memoryCapacity: 4 * 1024 * 1024, diskCapacity: 0)
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        let folder = caches.appendingPathComponent("chat.bipe.app.mac", isDirectory: true)
        for name in ["Uploads", "Cache.db", "Cache.db-shm", "Cache.db-wal", "fsCachedData"] {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
    }
}
