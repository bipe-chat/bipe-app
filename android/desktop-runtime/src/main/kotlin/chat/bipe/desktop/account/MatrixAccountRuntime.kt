// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.AccountLease
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import chat.bipe.desktop.storage.AccountRepository
import chat.bipe.desktop.chat.AccountDrafts
import chat.bipe.desktop.chat.SdkComposerDraftStore

sealed interface AccountSignal {
    data object Healthy : AccountSignal
    data class AuthenticationRejected(val softLogout: Boolean) : AccountSignal
    data class StorageUnavailable(val reason: StorageCode) : AccountSignal
}

/** A native client with a fixed storage lease; no Android Context/Keystore or globally selected account. */
class MatrixAccountRuntime private constructor(
    val lifetime: AccountLifetime,
    val client: Client,
    val drafts: AccountDrafts,
    private val mutableSignal: MutableStateFlow<AccountSignal>,
    val media: AccountMedia,
) {
    val slot get() = lifetime.lease.slot
    val signal = mutableSignal.asStateFlow()
    private val syncLock = Mutex()
    private sealed interface SyncPhase {
        data object Idle : SyncPhase
        class Running(val service: SyncService) : SyncPhase
    }
    private var sync: SyncPhase = SyncPhase.Idle
    private enum class PhoneLinkOwnership { AVAILABLE, CLAIMED, LINKED }
    private var phoneLinkOwnership = PhoneLinkOwnership.AVAILABLE

    /** A failed/cancelled login retires this pending device; another attempt starts in a new slot. */
    @Synchronized internal fun claimPhoneLink() {
        lifetime.requireCurrent()
        if (phoneLinkOwnership != PhoneLinkOwnership.AVAILABLE || lifetime.lease.savedSession() != null) {
            throw StorageFailure(StorageCode.LINK_INCOMPLETE)
        }
        phoneLinkOwnership = PhoneLinkOwnership.CLAIMED
    }

    /** Build/start sync as an owned task, so cancellation retires late construction as well as callbacks. */
    suspend fun startSync(): SyncService = try { lifetime.async { syncLock.withLock {
        lifetime.requireCurrent()
        (sync as? SyncPhase.Running)?.let { return@withLock it.service }
        val initial = client.syncService()
        val builder = try { initial.withOfflineMode() } finally { initial.destroy() }
        val service = try { builder.finish() } finally { builder.destroy() }
        lifetime.own(SyncResource(service))
        currentCoroutineContext().ensureActive()
        lifetime.requireCurrent()
        service.start()
        lifetime.requireCurrent()
        sync = SyncPhase.Running(service)
        service
    } }.await() }
    catch (error: Exception) {
        withContext(NonCancellable) { lifetime.closeAsync().await() }
        throw error
    }

    /** Token persistence stays pending until the phone approval and secret transfer have completed. */
    @Synchronized fun completePhoneLink(repository: AccountRepository) {
        lifetime.requireCurrent()
        repository.completeLink(lifetime.lease, client.session())
        phoneLinkOwnership = PhoneLinkOwnership.LINKED
    }

    fun closeAsync() = lifetime.closeAsync()

    companion object {
        private enum class Purpose { CLIENT, PENDING_LOGOUT }

        suspend fun open(lease: AccountLease, homeserver: String, userAgent: String): MatrixAccountRuntime =
            open(lease, homeserver, userAgent, Purpose.CLIENT)

        private suspend fun open(lease: AccountLease, homeserver: String, userAgent: String, purpose: Purpose): MatrixAccountRuntime {
            val lifetime = AccountLifetime(lease)
            val signal = MutableStateFlow<AccountSignal>(AccountSignal.Healthy)
            try {
                return lifetime.async {
                    val delegate = sessionDelegate(lifetime, signal)
                    val builder = lease.useStoreKey { key ->
                        configuredBuilder(lease, key, homeserver, userAgent, delegate, purpose)
                    }
                    val client = try { builder.build() } finally { builder.destroy() }
                    lifetime.own(ClientResource(client, lease))
                    val drafts = lifetime.own(AccountDrafts(lifetime, SdkComposerDraftStore(client)))
                    currentCoroutineContext().ensureActive()
                    lifetime.requireCurrent()
                    val auth = client.setDelegate(object : ClientDelegate {
                        override fun didReceiveAuthError(isSoftLogout: Boolean) {
                            lifetime.publish(lifetime.epoch) {
                                signal.value = AccountSignal.AuthenticationRejected(isSoftLogout)
                            }
                            lease.retire()
                            lifetime.closeAsync()
                        }
                        override fun onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) {}
                    })
                    // The SDK accepts a null delegate; this client always requires an owned auth listener.
                    lifetime.own(TaskResource(checkNotNull(auth) { "Missing SDK authentication listener" }))
                    val media = AccountMedia.create(lifetime, client)
                    MatrixAccountRuntime(lifetime, client, drafts, signal, media)
                }.await()
            } catch (error: Exception) {
                withContext(NonCancellable) { lifetime.closeAsync().await() }
                throw error
            }
        }

        private fun configuredBuilder(lease: AccountLease, key: ByteArray, homeserver: String,
                                      userAgent: String, delegate: ClientSessionDelegate, purpose: Purpose): ClientBuilder {
            val initialStore = SqliteStoreBuilder(lease.paths.data.toString(), lease.paths.cache.toString())
            val store = try { initialStore.key(key) } finally { initialStore.destroy() }
            var builder = ClientBuilder()
            fun update(operation: (ClientBuilder) -> ClientBuilder) {
                val old = builder
                builder = operation(old)
                old.destroy()
            }
            try {
                update { it.sqliteStore(store) }
                update { it.homeserverUrl(homeserver) }
                update { it.slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE) }
                update { it.setSessionDelegate(delegate) }
                update { it.userAgent(userAgent) }
                update { it.autoEnableCrossSigning(purpose == Purpose.CLIENT) }
                update { it.autoEnableBackups(purpose == Purpose.CLIENT) }
                update { it.roomKeyRecipientStrategy(CollectStrategy.IDENTITY_BASED_STRATEGY) }
                update { it.decryptionSettings(DecryptionSettings(TrustRequirement.CROSS_SIGNED_OR_LEGACY)) }
                update { it.backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE) }
                return builder
            } catch (error: Exception) { builder.destroy(); throw error }
            finally { store.destroy() }
        }

        suspend fun restore(lease: AccountLease, userAgent: String): MatrixAccountRuntime {
            // This fails before SDK construction if the record is pending, revoked or its store is missing.
            val saved = try { lease.linkedSession() }
            catch (error: Exception) { lease.close(); throw error }
            val runtime = open(lease, saved.homeserverUrl, userAgent)
            try {
                runtime.lifetime.async {
                    runtime.client.restoreSession(saved)
                    runtime.lifetime.requireCurrent()
                }.await()
                return runtime
            } catch (error: Exception) {
                withContext(NonCancellable) { runtime.closeAsync().await() }
                throw error
            }
        }

        /** Recovery-only restore: no sync, no promotion and no new phone-link attempt. */
        suspend fun restorePendingForLogout(lease: AccountLease, userAgent: String): MatrixAccountRuntime {
            val saved = try { lease.pendingSession() ?: throw StorageFailure(StorageCode.LINK_INCOMPLETE) }
            catch (error: Exception) { lease.close(); throw error }
            try { chat.bipe.desktop.storage.SdkStoreLayout.requireExisting(lease.paths) }
            catch (error: Exception) { lease.close(); throw error }
            val runtime = open(lease, saved.homeserverUrl, userAgent, Purpose.PENDING_LOGOUT)
            try {
                runtime.lifetime.async {
                    runtime.client.restoreSession(saved)
                    runtime.lifetime.requireCurrent()
                    val restored = runtime.client.session()
                    if (restored.userId != saved.userId || restored.deviceId != saved.deviceId ||
                        restored.homeserverUrl != saved.homeserverUrl) throw StorageFailure(StorageCode.IDENTITY_MISMATCH)
                }.await()
                return runtime
            } catch (error: Exception) {
                withContext(NonCancellable) { runtime.closeAsync().await() }
                throw error
            }
        }

        private fun sessionDelegate(lifetime: AccountLifetime, signal: MutableStateFlow<AccountSignal>) =
            object : ClientSessionDelegate {
                override fun retrieveSessionFromKeychain(userId: String): Session = guarded {
                    lifetime.requireCurrent()
                    val session = lifetime.lease.savedSession() ?: throw StorageFailure(StorageCode.LINK_INCOMPLETE)
                    if (session.userId != userId) throw StorageFailure(StorageCode.IDENTITY_MISMATCH)
                    session
                }

                override fun saveSessionInKeychain(session: Session) = guarded {
                    lifetime.requireCurrent()
                    lifetime.lease.persistSession(session)
                }

                private fun <T> guarded(operation: () -> T): T = try { operation() }
                catch (failure: StorageFailure) {
                    lifetime.publish(lifetime.epoch) { signal.value = AccountSignal.StorageUnavailable(failure.code) }
                    lifetime.closeAsync()
                    throw ClientException.Generic("local credential store unavailable", null)
                }
            }
    }
}

class TaskResource(private val task: TaskHandle) : AccountResource {
    override suspend fun close() { task.cancel(); task.destroy() }
}

private class SyncResource(private val service: SyncService) : AccountResource {
    private enum class Phase { OPEN, RELEASED }
    private var phase = Phase.OPEN
    override suspend fun close() {
        if (phase == Phase.RELEASED) return
        try { service.stop() }
        finally { service.destroy(); phase = Phase.RELEASED }
    }
}

private class ClientResource(private val client: Client, private val lease: AccountLease) : AccountResource {
    private enum class Phase { OPEN, DRAINING, RELEASED }
    private var phase = Phase.OPEN
    override suspend fun close() {
        if (phase == Phase.RELEASED) return
        if (phase == Phase.OPEN) { client.destroy(); phase = Phase.DRAINING }
        WindowsStoreDrain.await(lease.paths)
        phase = Phase.RELEASED
    }
}
