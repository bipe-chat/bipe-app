// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Native adapter for the pinned SDK's matrix-sdk-sqlite state/crypto DATABASE_NAME constants. */
object SdkStoreLayout {
    val databases = listOf("matrix-sdk-state.sqlite3", "matrix-sdk-crypto.sqlite3")

    fun requireDirectories(paths: AccountPaths) {
        for (path in listOf(paths.data, paths.cache, paths.drafts, paths.media)) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                !path.toRealPath().startsWith(paths.root.toRealPath())) throw StorageFailure(StorageCode.MISSING_STORE)
        }
    }

    fun requireExisting(paths: AccountPaths) {
        requireDirectories(paths)
        for (name in databases) {
            val file = paths.data.resolve(name)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) < 512 ||
                !file.toRealPath().startsWith(paths.root.toRealPath())) {
                throw StorageFailure(StorageCode.MISSING_STORE)
            }
        }
    }
}
