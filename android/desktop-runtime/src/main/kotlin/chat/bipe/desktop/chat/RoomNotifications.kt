// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_base.EncryptionState

enum class NotifyMode(val title: String) {
    QUIET("bipe_app_notify_quiet"), ALL("bipe_app_notify_all"), MUTE("bipe_app_notify_mute"), LEGACY("bipe_app_notify_mentions_legacy");
    internal fun sdk() = when (this) {
        ALL -> RoomNotificationMode.ALL_MESSAGES; MUTE -> RoomNotificationMode.MUTE
        QUIET, LEGACY -> RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
    }
    companion object {
        internal fun of(mode: RoomNotificationMode, dm: Boolean): NotifyMode = when (mode) {
            RoomNotificationMode.ALL_MESSAGES -> ALL; RoomNotificationMode.MUTE -> MUTE
            RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> if (dm) LEGACY else QUIET
        }
    }
}
data class NotifySnapshot(val isDm: Boolean, val encrypted: Boolean, val current: NotifyMode, val default: NotifyMode,
                          val repairLegacyGroup: Boolean = false) {
    val options get() = when {
        isDm -> listOfNotNull(NotifyMode.ALL, NotifyMode.MUTE, NotifyMode.LEGACY.takeIf { current == it })
        encrypted && default != NotifyMode.QUIET -> listOf(NotifyMode.ALL, NotifyMode.MUTE)
        else -> listOf(NotifyMode.QUIET, NotifyMode.ALL, NotifyMode.MUTE)
    }
}
sealed interface NotifySave {
    data object Idle : NotifySave
    data class Sending(val mode: NotifyMode) : NotifySave
    data class Failed(val mode: NotifyMode) : NotifySave
}
sealed interface NotifyPhase {
    data object Loading : NotifyPhase
    data object Failed : NotifyPhase
    data class Ready(val snapshot: NotifySnapshot, val save: NotifySave = NotifySave.Idle) : NotifyPhase
}
internal interface NotifyBackend {
    suspend fun read(): NotifySnapshot
    suspend fun restore()
    suspend fun unmute(snapshot: NotifySnapshot)
    suspend fun set(mode: NotifyMode)
}

/** A notification sheet is permanently bound to one account and room, including failures and retries. */
class RoomNotifications private constructor(val roomId: String, private val group: AccountTaskGroup, private val backend: NotifyBackend,
                                           private val transaction: Mutex) {
    private val mutablePhase = MutableStateFlow<NotifyPhase>(NotifyPhase.Loading)
    val phase = mutablePhase.asStateFlow()
    fun reload() {
        var admitted = false
        group.publish { if (mutablePhase.value != NotifyPhase.Loading && (mutablePhase.value as? NotifyPhase.Ready)?.save !is NotifySave.Sending) { mutablePhase.value = NotifyPhase.Loading; admitted = true } }
        if (admitted) group.launch { load() }
    }
    private suspend fun read(): NotifySnapshot {
        val snapshot = backend.read()
        group.requireCurrent()
        if (!snapshot.repairLegacyGroup) return snapshot
        backend.restore(); group.requireCurrent()
        return snapshot.copy(current = snapshot.default, repairLegacyGroup = false)
    }
    private suspend fun load() {
        try { val snapshot = transaction.withLock { read() }; group.publish { mutablePhase.value = NotifyPhase.Ready(snapshot) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { group.publish { mutablePhase.value = NotifyPhase.Failed } }
    }
    fun choose(mode: NotifyMode) {
        var old: NotifySnapshot? = null
        group.publish {
            val current = mutablePhase.value as? NotifyPhase.Ready
            if (current != null && current.save !is NotifySave.Sending && mode != NotifyMode.LEGACY && mode in current.snapshot.options && mode != current.snapshot.current) {
                old = current.snapshot; mutablePhase.value = current.copy(save = NotifySave.Sending(mode))
            }
        }
        val previous = old ?: return
        group.launch {
            try {
                transaction.withLock {
                    val fresh = read()
                    check(mode in fresh.options && mode != NotifyMode.LEGACY)
                    if (fresh.current == NotifyMode.MUTE && mode != NotifyMode.MUTE) { backend.unmute(fresh); group.requireCurrent() }
                    if (mode == fresh.default) backend.restore() else backend.set(mode)
                    group.requireCurrent()
                    group.publish { mutablePhase.value = NotifyPhase.Ready(fresh.copy(current = mode, repairLegacyGroup = false)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { mutablePhase.value = NotifyPhase.Ready(previous, NotifySave.Failed(mode)) } }
        }
    }
    fun retry() {
        when (val state = mutablePhase.value) {
            NotifyPhase.Failed -> reload()
            is NotifyPhase.Ready -> (state.save as? NotifySave.Failed)?.let { choose(it.mode) }
            else -> Unit
        }
    }
    companion object {
        suspend fun open(runtime: MatrixAccountRuntime, room: String): RoomNotifications = create(runtime.lifetime, room, object : NotifyBackend {
            override suspend fun read(): NotifySnapshot {
                val native = runtime.client.getRoom(room) ?: error("Notification room unavailable")
                val info = try { native.roomInfo() } finally { native.destroy() }
                val (dm, encrypted) = try {
                    check(info.membership == Membership.JOINED && !info.isSpace)
                    info.isDm to (info.encryptionState != EncryptionState.NOT_ENCRYPTED)
                } finally { info.destroy() }
                val settings = runtime.client.getNotificationSettings()
                try {
                    val fallback = NotifyMode.of(settings.getDefaultRoomNotificationMode(encrypted && dm, dm), dm)
                    val current = settings.getRoomNotificationSettings(room, encrypted, dm)
                    return NotifySnapshot(dm, encrypted, if (current.isDefault) fallback else NotifyMode.of(current.mode, dm), fallback,
                        !dm && encrypted && !current.isDefault && current.mode == RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
                } finally { settings.destroy() }
            }
            override suspend fun restore() { val settings = runtime.client.getNotificationSettings(); try { settings.restoreDefaultRoomNotificationMode(room) } finally { settings.destroy() } }
            override suspend fun unmute(snapshot: NotifySnapshot) { val settings = runtime.client.getNotificationSettings(); try { settings.unmuteRoom(room, snapshot.encrypted, snapshot.isDm) } finally { settings.destroy() } }
            override suspend fun set(mode: NotifyMode) { val settings = runtime.client.getNotificationSettings(); try { settings.setRoomNotificationMode(room, mode.sdk()) } finally { settings.destroy() } }
        }, runtime.notificationLock)
        internal suspend fun create(owner: AccountLifetime, room: String, backend: NotifyBackend, transaction: Mutex = Mutex()): RoomNotifications {
            val group = AccountTaskGroup.create(owner)
            return RoomNotifications(room, group, backend, transaction).also { model -> group.launch { model.load() } }
        }
    }
}
