// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.annotation.StringRes
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import uniffi.matrix_sdk_base.EncryptionState

/** Enum NotifyOption.mode of spec chat-notifications. */
enum class NotifyMode(@StringRes val title: Int) {
    QUIET(R.string.bipe_app_notify_quiet),
    ALL(R.string.bipe_app_notify_all),
    MUTE(R.string.bipe_app_notify_mute),
    MENTIONS_LEGACY(R.string.bipe_app_notify_mentions_legacy),
    ;

    val sdkMode: RoomNotificationMode
        get() = when (this) {
            ALL -> RoomNotificationMode.ALL_MESSAGES
            MUTE -> RoomNotificationMode.MUTE
            QUIET, MENTIONS_LEGACY -> RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
        }

    companion object {
        /** Mentions and keywords is quiet in a group and the old rule in a DM (ADR 0141). */
        fun of(mode: RoomNotificationMode, isDm: Boolean): NotifyMode = when (mode) {
            RoomNotificationMode.ALL_MESSAGES -> ALL
            RoomNotificationMode.MUTE -> MUTE
            RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> if (isDm) MENTIONS_LEGACY else QUIET
        }
    }
}

/** Type NotifyOption of spec chat-notifications. */
data class NotifyOption(val mode: NotifyMode, @StringRes val footer: Int?, val isDefault: Boolean)

/** Spec chat-notifications (bipe-shared product/screens/chat-notifications.json). */
class ChatNotificationsModel(
    private val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
) {
    private val _options = MutableStateFlow<List<NotifyOption>>(emptyList())
    val options: StateFlow<List<NotifyOption>> = _options.asStateFlow()
    private val _current = MutableStateFlow<NotifyMode?>(null)
    val current: StateFlow<NotifyMode?> = _current.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private var isDm = false
    private var isEncrypted = true
    private var defaultMode: NotifyMode? = null
    private var canPushEncrypted = true

    /** Action appear. */
    fun appear() {
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            val info = runCatching { room.roomInfo() }.getOrNull() ?: return@launch
            isDm = info.isDm
            isEncrypted = info.encryptionState != EncryptionState.NOT_ENCRYPTED
            val settings = session.client.getNotificationSettings()
            defaultMode = NotifyMode.of(settings.getDefaultRoomNotificationMode(isEncrypted, isDm), isDm)
            canPushEncrypted = settings.canHomeserverPushEncryptedEventToDevice()
            runCatching { settings.getRoomNotificationSettings(roomId, isEncrypted, isDm) }.getOrNull()?.let {
                _current.value = NotifyMode.of(it.mode, isDm)
            }
            rebuildOptions()
        }
    }

    /** Action choose. */
    fun choose(mode: NotifyMode) {
        val old = _current.value ?: return
        if (_saving.value || mode == NotifyMode.MENTIONS_LEGACY || mode == old) return
        _saving.value = true
        _current.value = mode
        scope.launch {
            runCatching {
                val settings = session.client.getNotificationSettings()
                if (old == NotifyMode.MUTE) settings.unmuteRoom(roomId, isEncrypted, isDm)
                if (mode == defaultMode) {
                    settings.restoreDefaultRoomNotificationMode(roomId)
                } else {
                    settings.setRoomNotificationMode(roomId, mode.sdkMode)
                }
            }.onSuccess {
                rebuildOptions()
            }.onFailure {
                _current.value = old
                _error.value = true
            }
            _saving.value = false
        }
    }

    fun dismissError() {
        _error.value = false
    }

    private fun rebuildOptions() {
        val current = _current.value
        var quietFooter: Int? = R.string.bipe_app_notify_quiet_footer
        val modes = when {
            isDm -> listOfNotNull(NotifyMode.ALL, NotifyMode.MUTE, NotifyMode.MENTIONS_LEGACY.takeIf { current == it })
            isEncrypted && !canPushEncrypted -> {
                // Quiet would silence the group until the server pushes encrypted events (#118).
                quietFooter = null
                listOfNotNull(NotifyMode.QUIET.takeIf { current == it }, NotifyMode.ALL, NotifyMode.MUTE)
            }
            else -> listOf(NotifyMode.QUIET, NotifyMode.ALL, NotifyMode.MUTE)
        }
        _options.value = modes.map { NotifyOption(it, if (it == NotifyMode.QUIET) quietFooter else null, it == defaultMode) }
    }

    companion object {
        /** The current mode of a chat, for the row in group-details. */
        suspend fun currentMode(client: Client, roomId: String): NotifyMode? {
            val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
            val info = runCatching { room.roomInfo() }.getOrNull() ?: return null
            val settings = client.getNotificationSettings()
            val roomSettings = runCatching {
                settings.getRoomNotificationSettings(roomId, info.encryptionState != EncryptionState.NOT_ENCRYPTED, info.isDm)
            }.getOrNull() ?: return null
            return NotifyMode.of(roomSettings.mode, info.isDm)
        }
    }
}
