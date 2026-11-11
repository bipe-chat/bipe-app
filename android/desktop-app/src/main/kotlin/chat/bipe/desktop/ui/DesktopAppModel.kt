// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import chat.bipe.desktop.account.*
import chat.bipe.desktop.chat.*
import chat.bipe.desktop.link.*
import chat.bipe.desktop.storage.*
import chat.bipe.desktop.settings.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class AccountKind { PERSONAL, COMPANY, UNKNOWN }
data class AccountProfile(val name: String, val kind: AccountKind, val avatar: String?)

sealed interface AppPhase {
    data object Loading : AppPhase
    data class Welcome(val slots: List<StoredSlot>) : AppPhase
    class Linking(val runtime: MatrixAccountRuntime, val attempt: PhoneLinkAttempt) : AppPhase
    class Recovering(val recovery: PendingDeviceRecovery, val source: StoredSlot) : AppPhase
    class Ready(val runtime: MatrixAccountRuntime, val list: DesktopRoomList, val profile: StateFlow<AccountProfile>,
                val slots: List<StoredSlot>, val appearance: AccountAppearance) : AppPhase
    data class Failed(val needsPhone: Boolean = false) : AppPhase
    class Closing(val source: AppPhase) : AppPhase
    class CloseFailed(val source: AppPhase) : AppPhase
}

sealed interface SelectedTimeline {
    data object Empty : SelectedTimeline
    class Opening(val roomId: String, val retained: DesktopTimeline?) : SelectedTimeline
    class Showing(val model: DesktopTimeline) : SelectedTimeline
    class Failed(val retained: DesktopTimeline? = null) : SelectedTimeline
}

private fun SelectedTimeline.owned(): DesktopTimeline? = when (this) {
    is SelectedTimeline.Opening -> retained
    is SelectedTimeline.Showing -> model
    is SelectedTimeline.Failed -> retained
    SelectedTimeline.Empty -> null
}

/** Serial account transitions; rendering never selects a global SDK client or receives a native Room. */
class DesktopAppModel(val catalog: DesktopCatalog) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deviceAppearance = DeviceAppearance(catalog.appearanceOptions.map { it.first }, scope)
    private val transitions = Mutex()
    private val repository by lazy { AccountRepository.windowsDefault() }
    private val mutablePhase = MutableStateFlow<AppPhase>(AppPhase.Loading)
    val phase = mutablePhase.asStateFlow()
    private val mutableTimeline = MutableStateFlow<SelectedTimeline>(SelectedTimeline.Empty)
    val timeline = mutableTimeline.asStateFlow()
    private val roomTransitions = Mutex()
    @Volatile private var roomRequest = 0L

    init { refresh() }

    fun refresh() = scope.launch { transitions.withLock {
        val previous = mutablePhase.value
        if (previous is AppPhase.Closing || previous is AppPhase.CloseFailed) return@withLock
        show(AppPhase.Loading)
        try {
            close(previous)
            retryCleanup()
            show(AppPhase.Welcome(repository.slots()))
        } catch (_: Exception) { show(AppPhase.Failed()) }
    } }

    fun open(slot: StoredSlot) = scope.launch { transitions.withLock {
        val previous = mutablePhase.value
        if (previous is AppPhase.Closing || previous is AppPhase.CloseFailed) return@withLock
        show(AppPhase.Loading)
        try {
            close(previous)
            if (slot is StoredSlot.Pending || slot is StoredSlot.Discarding || slot is StoredSlot.Revoked) {
                show(AppPhase.Recovering(PendingDeviceRecovery(slot.slot, repository, SdkPendingLogout(userAgent())), slot))
                return@withLock
            }
            if (slot !is StoredSlot.Linked) { show(AppPhase.Failed(needsPhone = true)); return@withLock }
            val runtime = MatrixAccountRuntime.restore(repository.open(slot.slot), userAgent())
            connect(runtime)
        } catch (_: Exception) { show(AppPhase.Failed()) }
    } }

    fun recoverPending(action: PendingRecoveryAction) {
        val recovery = mutablePhase.value as? AppPhase.Recovering ?: return
        val expected = recovery.recovery.phase.value
        scope.launch { transitions.withLock {
            if (mutablePhase.value === recovery && recovery.recovery.phase.value === expected) recovery.recovery.run(action)
        } }
    }

    fun link() = scope.launch { transitions.withLock {
        if (mutablePhase.value is AppPhase.Linking || mutablePhase.value == AppPhase.Loading ||
            mutablePhase.value is AppPhase.Closing || mutablePhase.value is AppPhase.CloseFailed) return@withLock
        val previous = mutablePhase.value
        show(AppPhase.Loading)
        try {
            close(previous)
            val registration = WindowsLoginRegistration(catalog.windowsClientId ?: throw PhoneLinkException(PhoneLinkFailure.REGISTRATION_REQUIRED))
            val runtime = MatrixAccountRuntime.open(repository.provision(), registration.homeserver, userAgent())
            val attempt = try { PhoneLinkAttempt.create(runtime, repository, registration) }
                catch (error: Exception) { runtime.closeAsync().await(); throw error }
            val linking = AppPhase.Linking(runtime, attempt)
            show(linking)
            attempt.start()
            scope.launch {
                attempt.phase.first { it == PhoneLinkPhase.Linked || it is PhoneLinkPhase.Failed || it == PhoneLinkPhase.Cancelled }
                transitions.withLock {
                    if (mutablePhase.value !== linking) return@withLock
                    if (attempt.phase.value == PhoneLinkPhase.Linked) {
                        try { connect(runtime) } catch (_: Exception) { runtime.closeAsync().await(); show(AppPhase.Failed()) }
                    }
                }
            }
        } catch (_: Exception) { show(AppPhase.Failed()) }
    } }

    /** Cancellation preserves the pending slot for explicit recovery. It never claims a partial server device was removed. */
    fun cancelLink() = scope.launch { transitions.withLock {
        val linking = mutablePhase.value as? AppPhase.Linking ?: return@withLock
        try {
            linking.attempt.cancel()
            if (linking.runtime.closeAsync().await() != AccountCloseResult.Closed) {
                show(AppPhase.Failed(needsPhone = true))
            } else show(AppPhase.Welcome(repository.slots()))
        } catch (_: Exception) { show(AppPhase.Failed(needsPhone = true)) }
    } }

    fun selectRoom(roomId: String) {
        val current = mutablePhase.value as? AppPhase.Ready ?: return
        when (val selected = mutableTimeline.value) {
            is SelectedTimeline.Showing -> if (selected.model.roomId == roomId) return
            is SelectedTimeline.Opening -> if (selected.roomId == roomId) return
            else -> Unit
        }
        val request = synchronized(this) { ++roomRequest }
        mutableTimeline.value = SelectedTimeline.Opening(roomId, mutableTimeline.value.owned())
        scope.launch { roomTransitions.withLock {
            if (request != roomRequest || mutablePhase.value !== current) return@withLock
            // Every previous room is owned by the same immutable account; closing it cannot affect another account.
            val previous = mutableTimeline.value.owned()
            try {
                previous?.close()
                val model = DesktopTimeline.open(current.runtime, roomId, catalog.chatLabels())
                if (request != roomRequest || mutablePhase.value !== current) model.close()
                else mutableTimeline.value = SelectedTimeline.Showing(model)
            } catch (_: Exception) {
                if (request == roomRequest && mutablePhase.value === current) mutableTimeline.value = SelectedTimeline.Failed(previous)
            }
        } }
    }

    fun clearRoom() {
        val request = synchronized(this) { ++roomRequest }
        scope.launch { roomTransitions.withLock {
            if (request != roomRequest) return@withLock
            try { mutableTimeline.value.owned()?.close(); mutableTimeline.value = SelectedTimeline.Empty }
            catch (_: Exception) { mutableTimeline.value = SelectedTimeline.Failed(mutableTimeline.value.owned()) }
        } }
    }

    @Synchronized fun closeWindow(onClosed: () -> Unit) {
        val previous = mutablePhase.value
        if (previous is AppPhase.Closing) return
        val source = (previous as? AppPhase.CloseFailed)?.source ?: previous
        val closing = AppPhase.Closing(source)
        // Remove interactive account views immediately; repeated Close cannot enqueue a second teardown.
        mutablePhase.value = closing
        scope.launch {
            try { transitions.withLock {
                val latest = (mutablePhase.value as? AppPhase.Closing)?.source ?: source
                close(latest)
                retryCleanup()
            } }
            catch (_: Exception) {
                val latest = (mutablePhase.value as? AppPhase.Closing)?.source ?: source
                mutablePhase.value = AppPhase.CloseFailed(latest)
                return@launch
            }
            withContext(NonCancellable + Dispatchers.Main) { onClosed() }
            scope.cancel()
        }
    }

    /** A Close requested during restore/linking keeps its intent while that serial operation settles. */
    @Synchronized private fun show(next: AppPhase) {
        mutablePhase.value = if (mutablePhase.value is AppPhase.Closing) AppPhase.Closing(next) else next
    }

    private suspend fun retryCleanup() {
        for (epoch in AccountLifetime.pendingCleanup().keys) {
            if (AccountLifetime.retryCleanup(epoch)?.await() != AccountCloseResult.Closed) throw StorageFailure(StorageCode.IO_FAILURE)
        }
    }

    private suspend fun close(phase: AppPhase) {
        synchronized(this) { roomRequest++ }
        val runtime = when (phase) {
            is AppPhase.Ready -> phase.runtime
            is AppPhase.Linking -> { phase.attempt.cancel(); phase.runtime }
            is AppPhase.Recovering -> {
                if (!phase.recovery.close()) throw StorageFailure(StorageCode.SLOT_BUSY)
                null
            }
            else -> null
        }
        try { roomTransitions.withLock { mutableTimeline.value.owned()?.close() } }
        finally {
            mutableTimeline.value = SelectedTimeline.Empty
            if (runtime != null && runtime.closeAsync().await() != AccountCloseResult.Closed) throw StorageFailure(StorageCode.IO_FAILURE)
        }
    }

    private suspend fun connect(runtime: MatrixAccountRuntime) {
        try {
            val list = DesktopRoomList.open(runtime, catalog.chatLabels())
            val profile = runtime.lifetime.async {
                val userId = runtime.client.userId()
                val name = realName(runCatching { runtime.client.displayName() }.getOrNull(), userId, catalog.text("bipe_tab_you"))
                AccountProfile(name, accountKind(userId), runCatching { runtime.client.avatarUrl() }.getOrNull())
            }.await()
            val liveProfile = MutableStateFlow(profile)
            val appearance = AccountAppearance.open(runtime, catalog.appearancePolicy)
            val ready = AppPhase.Ready(runtime, list, liveProfile.asStateFlow(), repository.slots(), appearance)
            show(ready)
            scope.launch {
                runtime.signal.first { it != AccountSignal.Healthy }
                transitions.withLock {
                    if (mutablePhase.value === ready) {
                        close(ready)
                        show(AppPhase.Failed(needsPhone = true))
                    }
                }
            }
        } catch (error: Exception) { runtime.closeAsync().await(); throw error }
    }

    private suspend fun accountKind(userId: String): AccountKind = try {
        runInterruptible {
            val path = URLEncoder.encode(userId, StandardCharsets.UTF_8).replace("+", "%20")
            val request = HttpRequest.newBuilder(URI("https://api.bipe.net.br/v1/accounts/$path/type"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .build().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) AccountKind.UNKNOWN
            else when (Json.parseToJsonElement(response.body()).jsonObject["account_type"]?.jsonPrimitive?.content) {
                "company" -> AccountKind.COMPANY
                "personal" -> AccountKind.PERSONAL
                else -> AccountKind.UNKNOWN
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { AccountKind.UNKNOWN }

    private fun userAgent() = "Bipe Windows/${catalog.build.getValue("version").jsonPrimitive.content} (${catalog.build.getValue("build").jsonPrimitive.content})"
}
