// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.util.Base64
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.account.BipeAccountVault
import java.io.File
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService

/** A signed-in account: the SDK client and its sync service. */
class AccountSession(val client: Client, val syncService: SyncService, val userId: String) {
    /** The access token for bipe-api calls (spec handle-pick notes). Read each time: the SDK may refresh it. */
    fun accessToken(): String? = runCatching { client.session().accessToken }.getOrNull()

    /** Signs out on the server and removes this account's session, store and hidden password from this phone. */
    suspend fun signOut(context: Context, sessionStore: SessionStore, vault: BipeAccountVault) {
        runCatching { client.logout() }
        syncService.stop()
        sessionStore.delete(userId)
        // The recovery token stays, so recovery-start finds it (spec sign-out). Only the hidden password goes.
        vault.update { data ->
            val kept = data.accounts[userId]?.copy(password = null)?.takeIf { it.token != null }
            data.copy(accounts = if (kept != null) data.accounts + (userId to kept) else data.accounts - userId)
        }
        deleteStore(context, userId)
    }

    companion object {
        /** Signs in with the hidden password (MAS compatibility login, m.login.password), saves the session, starts the sync. */
        suspend fun signIn(context: Context, userId: String, password: String, sessionStore: SessionStore): AccountSession {
            deleteStore(context, userId)
            val client = makeClient(context, userId, sessionStore)
            client.login(userId, password, AppConfig.DEVICE_NAME, null)
            sessionStore.save(client.session())
            return start(client)
        }

        /** Restores a saved session and starts the sync. */
        suspend fun restore(context: Context, saved: Session, sessionStore: SessionStore): AccountSession {
            val client = makeClient(context, saved.userId, sessionStore)
            client.restoreSession(saved)
            return start(client)
        }

        private suspend fun start(client: Client): AccountSession {
            val syncService = client.syncService().finish()
            syncService.start()
            return AccountSession(client, syncService, client.userId())
        }

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
