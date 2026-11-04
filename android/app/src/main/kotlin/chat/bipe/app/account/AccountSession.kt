// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.util.Base64
import android.util.Log
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.account.BipeStoreKey
import chat.bipe.shared.BipeRoomSubscriptions
import chat.bipe.shared.api.BipeOpenIdTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.SendQueueRoomErrorListener
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason

/** A signed-in account: the SDK client and its sync service. */
class AccountSession(val client: Client, val syncService: SyncService, val userId: String) {
    val roomSubscriptions = BipeRoomSubscriptions { ids -> syncService.roomListService().subscribeToRooms(ids) }
    private val subscriptionCleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cleanup must outlive a discarded UI scope; a closed account rejects subsequent updates. */
    fun releaseRoomSubscriptions(owner: String) { subscriptionCleanup.launch { roomSubscriptions.remove(owner) } }
    /** Spec soft-logout: the SDK reports a refused token here. Set by account-start; called on a background thread. */
    @Volatile var onAuthError: ((isSoftLogout: Boolean) -> Unit)? = null
    private var authErrorsHandle: TaskHandle? = null

    private fun watchAuthErrors() {
        authErrorsHandle = runCatching {
            client.setDelegate(object : ClientDelegate {
                override fun didReceiveAuthError(isSoftLogout: Boolean) { onAuthError?.invoke(isSoftLogout) }
                override fun onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) {}
            })
        }.getOrNull()
    }

    private var sendQueueHandle: TaskHandle? = null

    /**
     * Switches a room's send queue on again after the SDK switched it off (bipe-chat/bipe#947). matrix-rust-sdk turns a
     * room's queue off after a failed send and never turns it on by itself, so messages stayed on "Sending…".
     */
    private fun watchSendQueues() {
        sendQueueHandle = runCatching {
            client.subscribeToSendQueueStatus(object : SendQueueRoomErrorListener {
                override fun onError(roomId: String, error: ClientException) {
                    Log.w(AccountStep.TAG, "send queue: off after an error, on again in 15 s")
                    subscriptionCleanup.launch {
                        delay(15_000)
                        runCatching { client.getRoom(roomId)?.enableSendQueue(true) }
                    }
                }
            })
        }.getOrNull()
    }

    /** Switches every room's send queue on again, for a queue the SDK left off after a failed send (bipe-chat/bipe#947). */
    suspend fun resumeSending() {
        runCatching { client.enableAllSendQueues(true) }
    }

    /** Stops the sync and the room subscriptions and keeps everything on this phone (spec soft-logout). */
    suspend fun pause() {
        if (deliveredAcks.isInitialized()) delivered.stop()
        roomSubscriptions.close()
        syncService.stop()
    }

    /** Spec read-receipts: the delivered acks of this account (ADR 0222), for the chat list and the push handler. */
    private val deliveredAcks = lazy { chat.bipe.app.timeline.DeliveredAcks(client, userId) }
    val delivered: chat.bipe.app.timeline.DeliveredAcks by deliveredAcks

    /** The access token for bipe-api calls (spec handle-pick notes). Read each time: the SDK may refresh it. */
    fun accessToken(): String? = runCatching { client.session().accessToken }.getOrNull()

    /** The Matrix OpenID token for the core bipe-api routes (bipe-chat/bipe#646). Memory only. */
    val openId = BipeOpenIdTokens(client.homeserver(), userId, accessToken = { accessToken() })

    /** Signs out on the server and removes this account's session, store and hidden password from this phone. */
    suspend fun signOut(context: Context, sessionStore: SessionStore, vault: BipeAccountVault, alreadyLoggedOut: Boolean = false) {
        if (deliveredAcks.isInitialized()) delivered.stop()
        roomSubscriptions.close()
        subscriptionCleanup.cancel()
        if (!alreadyLoggedOut) runCatching { client.logout() }
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
        /**
         * Signs in with the hidden password (MAS compatibility login, m.login.password), saves the session, starts the
         * sync. Every step has a time limit ([AccountStep]). When this phone still holds the account's store, the login
         * keeps that store and its device ID, so the cross-signing keys and the room keys stay (bipe-chat/bipe#909).
         * Only a store without a known device ID goes.
         */
        suspend fun signIn(context: Context, userId: String, password: String, sessionStore: SessionStore): AccountSession {
            val keptDeviceId = if (hasStore(context, userId)) storedDeviceId(context, userId) else null
            if (keptDeviceId == null) deleteStore(context, userId)
            val client = AccountStep.run("build client") { makeClient(context, userId, sessionStore) }
            AccountStep.run("login") { client.login(userId, password, AppConfig.DEVICE_NAME, keptDeviceId) }
            sessionStore.save(client.session())
            return start(client, context)
        }

        /**
         * Spec soft-logout authError: signs in again with the hidden password on the same store and device ID, so keys
         * and history stay. The caller pauses the expired session first.
         */
        suspend fun signInAgain(context: Context, expired: AccountSession, password: String, sessionStore: SessionStore): AccountSession {
            val deviceId = expired.client.deviceId()
            val client = AccountStep.run("build client") { makeClient(context, expired.userId, sessionStore) }
            AccountStep.run("login") { client.login(expired.userId, password, AppConfig.DEVICE_NAME, deviceId) }
            sessionStore.save(client.session())
            return start(client, context)
        }

        /** true when the server refused the hidden password (M_FORBIDDEN), not a network or local failure. */
        fun isRefused(error: Throwable): Boolean = (error as? ClientException.MatrixApi)?.code == "M_FORBIDDEN"

        /** Restores a saved session and starts the sync. Every step has a time limit ([AccountStep]). */
        suspend fun restore(context: Context, saved: Session, sessionStore: SessionStore): AccountSession {
            // A session without its SDK store cannot restore.
            if (!hasStore(context, saved.userId)) throw AccountStep.NoStore()
            val client = AccountStep.run("build client") { makeClient(context, saved.userId, sessionStore, saved.homeserverUrl) }
            AccountStep.run("restore session") { client.restoreSession(saved) }
            return start(client, context)
        }

        private suspend fun start(client: Client, context: Context): AccountSession {
            val syncService = AccountStep.run("sync service") {
                // start() spawns SDK network tasks; offline mode retries when connectivity returns.
                // Initial sync/reachability responses must never gate local readiness.
                client.syncService().withOfflineMode().finish().also { it.start() }
            }
            return AccountSession(client, syncService, client.userId()).also {
                runCatching { saveDeviceId(context, it.userId, client.deviceId()) }
                it.watchAuthErrors()
                it.watchSendQueues()
            }
        }

        /** true when the account's SDK store folder exists and holds files. */
        fun hasStore(context: Context, userId: String): Boolean =
            File(File(context.noBackupFilesDir, "sessions"), folderName(userId)).list().orEmpty().isNotEmpty()

        /**
         * Builds a client whose store lives in app storage, one folder per account
         * (spec account-start notes: the switch from the fork can reuse it).
         */
        suspend fun makeClient(context: Context, userId: String, sessionStore: SessionStore,
                               homeserverUrl: String = AppConfig.HOMESERVER): Client =
            builder(context, storePaths(context, folderName(userId)), sessionStore).homeserverUrl(homeserverUrl).build()

        /** The settings every client of the app shares; the caller names the server. */
        suspend fun builder(context: Context, paths: Pair<File, File>, sessionStore: SessionStore): ClientBuilder {
            val (data, cache) = paths
            val storeKey = storeKey(context, data, cache)
            return ClientBuilder()
                .sqliteStore(SqliteStoreBuilder(data.path, cache.path).key(storeKey))
                // Discovery requests /versions during build, blocking offline local restore.
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .setSessionDelegate(sessionStore.delegate())
                // Spec encryption-status: a new account gets cross-signing and key backup with no screen.
                .autoEnableCrossSigning(true)
                .autoEnableBackups(true)
                // Security rule 13: a server-added device receives no keys without its owner's signature.
                .roomKeyRecipientStrategy(CollectStrategy.IDENTITY_BASED_STRATEGY)
                // Keep pre-trust-metadata history readable; current senders must be cross-signed.
                .decryptionSettings(DecryptionSettings(TrustRequirement.CROSS_SIGNED_OR_LEGACY))
                // Old messages decrypt from the key backup once this phone has the backup key (recovery-key-enter).
                .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
                // Spec chat-search notes (bipe-chat/bipe#671): the SDK's local message index.
                .withSearchIndexStore(File(data, "SearchIndex").apply { mkdirs() }.path,
                                      storeKey?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
        }

        /**
         * The key of the store in [data] (bipe-chat/bipe#913). A new store gets the install's key and a marker file, so
         * it never opens without the key. A store from before the key stays plain until its next fresh sign-in, with no
         * migration (MZ, 3 Nov 2026). Throws when a keyed store's key cannot be read: the store stays, closed.
         */
        private suspend fun storeKey(context: Context, data: File, cache: File): ByteArray? = withContext(Dispatchers.IO) {
            val marker = File(data, "bipe-store-keyed")
            when {
                marker.exists() -> checkNotNull(BipeStoreKey(context).read()) { "store key missing" }
                checkNotNull(data.list()).isNotEmpty() -> null
                else -> BipeStoreKey(context).readOrCreate().also {
                    // A cache left without its data folder is plain, and a keyed client cannot open it.
                    cache.deleteRecursively()
                    marker.writeText("")
                }
            }
        }

        /**
         * The device ID the account's store belongs to, kept in the store folder so it goes with the store
         * (bipe-chat/bipe#909). The saved session can be gone (soft logout, PIN recovery) while the store stays.
         */
        fun storedDeviceId(context: Context, userId: String): String? =
            runCatching { deviceIdFile(storePaths(context, folderName(userId)).first).readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }

        private fun saveDeviceId(context: Context, userId: String, deviceId: String) {
            deviceIdFile(storePaths(context, folderName(userId)).first).writeText(deviceId)
        }

        private fun deviceIdFile(data: File) = File(data, "bipe-device-id")

        /**
         * Deletes the store of an account. A new sign-in without a known device ID needs an empty store: the crypto
         * store belongs to one device.
         */
        fun deleteStore(context: Context, userId: String) = deleteStoreFolder(context, folderName(userId))

        fun deleteStoreFolder(context: Context, folder: String) {
            val (data, cache) = storePaths(context, folder)
            data.deleteRecursively()
            cache.deleteRecursively()
        }

        fun folderName(userId: String): String =
            Base64.encodeToString(userId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        /** The data and cache folders of one store, made when missing. */
        fun storePaths(context: Context, folder: String): Pair<File, File> {
            val data = File(File(context.noBackupFilesDir, "sessions"), folder).apply { mkdirs() }
            val cache = File(File(context.cacheDir, "sessions"), folder).apply { mkdirs() }
            return data to cache
        }
    }
}
