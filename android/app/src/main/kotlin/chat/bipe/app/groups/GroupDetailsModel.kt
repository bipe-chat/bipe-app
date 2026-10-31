// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import chat.bipe.app.account.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.ruma_events.StateEventType

/** Spec group-details (bipe-shared product/screens/group-details.json). */
class GroupDetailsModel(
    val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
) {
    enum class Phase { VIEWING, EDITING, SAVING }

    /** The photo in the edit form. */
    sealed interface PhotoEdit {
        data object Unchanged : PhotoEdit
        class New(val mimeType: String, val bytes: ByteArray) : PhotoEdit
        data object Removed : PhotoEdit
    }

    data class Info(val name: String, val avatarUrl: String?, val description: String?, val memberCount: Int)

    private val _info = MutableStateFlow(Info("", null, null, 0))
    val info: StateFlow<Info> = _info.asStateFlow()
    private val _notificationMode = MutableStateFlow<NotifyMode?>(null)
    val notificationMode: StateFlow<NotifyMode?> = _notificationMode.asStateFlow()
    private val _canEdit = MutableStateFlow(false)
    val canEdit: StateFlow<Boolean> = _canEdit.asStateFlow()
    private val _canInvite = MutableStateFlow(false)
    val canInvite: StateFlow<Boolean> = _canInvite.asStateFlow()
    private val _canChangePrivacy = MutableStateFlow(false)
    /** Spec group-details notes, privacy (bipe-chat/bipe#772): can change the join rule. */
    val canChangePrivacy: StateFlow<Boolean> = _canChangePrivacy.asStateFlow()
    private val _phase = MutableStateFlow(Phase.VIEWING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()

    val editName = MutableStateFlow("")
    val editDescription = MutableStateFlow("")
    val editPhoto = MutableStateFlow<PhotoEdit>(PhotoEdit.Unchanged)

    private var handle: TaskHandle? = null

    private fun room(): Room? = runCatching { session.client.getRoom(roomId) }.getOrNull()

    /** Action appear: RoomInfo, its updates, the power levels and the notification mode. */
    fun appear() {
        val room = room() ?: return
        scope.launch {
            runCatching { room.roomInfo() }.getOrNull()?.let(::applyInfo)
            refresh()
        }
        if (handle != null) return
        val updates = Channel<RoomInfo>(Channel.CONFLATED)
        handle = room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                updates.trySend(roomInfo)
            }
        })
        scope.launch {
            for (info in updates) applyInfo(info)
        }
    }

    /** Reads the power levels and the notification mode again, when the screen comes back. */
    fun refresh() {
        val room = room() ?: return
        scope.launch {
            runCatching { room.getPowerLevels() }.getOrNull()?.let { levels ->
                _canEdit.value = levels.canOwnUserSendState(StateEventType.RoomName)
                _canInvite.value = levels.canOwnUserInvite()
                _canChangePrivacy.value = levels.canOwnUserSendState(StateEventType.RoomJoinRules)
            }
            _notificationMode.value = ChatNotificationsModel.currentMode(session.client, roomId)
        }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    /** Action edit. */
    fun edit() {
        if (!_canEdit.value || _phase.value != Phase.VIEWING) return
        editName.value = _info.value.name
        editDescription.value = _info.value.description.orEmpty()
        editPhoto.value = PhotoEdit.Unchanged
        _phase.value = Phase.EDITING
    }

    /** Action cancelEdit. */
    fun cancelEdit() {
        _phase.value = Phase.VIEWING
    }

    fun canSave(name: String, phase: Phase): Boolean = phase == Phase.EDITING && name.isNotBlank()

    /** Action save: sends only what changed. */
    fun save() {
        if (!canSave(editName.value, _phase.value)) return
        val room = room() ?: return
        _phase.value = Phase.SAVING
        scope.launch {
            runCatching {
                val current = _info.value
                val newName = editName.value.trim()
                if (newName != current.name) room.setName(newName)
                val newDescription = editDescription.value.trim()
                if (newDescription != current.description.orEmpty()) room.setTopic(newDescription)
                when (val photo = editPhoto.value) {
                    PhotoEdit.Unchanged -> Unit
                    is PhotoEdit.New -> room.uploadAvatar(photo.mimeType, photo.bytes, null)
                    PhotoEdit.Removed -> room.removeAvatar()
                }
            }.onSuccess {
                _phase.value = Phase.VIEWING
            }.onFailure {
                _phase.value = Phase.EDITING
                _saveError.value = true
            }
        }
    }

    fun dismissSaveError() {
        _saveError.value = false
    }

    private fun applyInfo(info: RoomInfo) {
        _info.value = Info(
            name = info.displayName ?: info.id,
            avatarUrl = info.avatarUrl,
            description = info.topic?.takeIf { it.isNotEmpty() },
            memberCount = info.joinedMembersCount.toInt(),
        )
    }
}
