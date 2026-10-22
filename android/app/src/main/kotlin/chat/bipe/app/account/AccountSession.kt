// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.util.Base64
import chat.bipe.app.common.AppConfig
import java.io.File
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService

/** A signed-in account: the SDK client and its sync service. */
class AccountSession(val client: Client, val syncService: SyncService, val userId: String) {
    companion object {
        /**
         * Builds a client whose store lives in app storage, one folder per account
         * (spec account-start notes: the switch from the fork can reuse it).
         */
        suspend fun makeClient(context: Context, userId: String, sessionStore: SessionStore): Client {
            val (data, cache) = storePaths(context, userId)
            return ClientBuilder()
                .serverNameOrHomeserverUrl(AppConfig.HOMESERVER)
                .sessionPaths(data.path, cache.path)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
                .setSessionDelegate(sessionStore.delegate())
                .build()
        }

        /** Deletes the store of an account. A new sign-in needs an empty store: the crypto store belongs to one device. */
        fun deleteStore(context: Context, userId: String) {
            val (data, cache) = storePaths(context, userId)
            data.deleteRecursively()
            cache.deleteRecursively()
        }

        private fun storePaths(context: Context, userId: String): Pair<File, File> {
            val folder = Base64.encodeToString(userId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val data = File(File(context.noBackupFilesDir, "sessions"), folder).apply { mkdirs() }
            val cache = File(File(context.cacheDir, "sessions"), folder).apply { mkdirs() }
            return data to cache
        }
    }
}
