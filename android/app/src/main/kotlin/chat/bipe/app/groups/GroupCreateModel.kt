// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.net.Uri
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.generated.BipeDmPowerLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

/** Spec group-create (bipe-shared product/screens/group-create.json). */
class GroupCreateModel(
    val session: AccountSession,
    private val scope: CoroutineScope,
    people: List<Person> = emptyList(),
) {
    enum class Phase { EDITING, CREATING, FAILED }

    /** The photo picked in the system picker, read and not yet uploaded. */
    data class Photo(val uri: Uri, val mimeType: String, val bytes: ByteArray)

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()
    private val _people = MutableStateFlow(people)
    val people: StateFlow<List<Person>> = _people.asStateFlow()
    private val _phase = MutableStateFlow(Phase.EDITING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _isQuietDefault = MutableStateFlow(false)
    val isQuietDefault: StateFlow<Boolean> = _isQuietDefault.asStateFlow()

    fun canCreate(name: String, phase: Phase): Boolean = name.isNotBlank() && phase != Phase.CREATING

    /** bipe_app_group_create_quiet_footer shows only when the default for groups is quiet. */
    fun appear() {
        scope.launch {
            val settings = session.client.getNotificationSettings()
            _isQuietDefault.value =
                settings.getDefaultRoomNotificationMode(true, false) == RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
        }
    }

    /** Action setName. */
    fun setName(name: String) {
        _name.value = name
        if (_phase.value == Phase.FAILED) _phase.value = Phase.EDITING
    }

    /** Action choosePhoto, after the system picker. */
    fun setPhoto(photo: Photo?) {
        _photo.value = photo
    }

    /** Action removePhoto. */
    fun removePhoto() {
        _photo.value = null
    }

    /** Action addPeople: group-invite-members returns the people, which replace the list. */
    fun setPeople(people: List<Person>) {
        _people.value = people
    }

    /** Action removePerson. */
    fun removePerson(userId: String) {
        _people.value = _people.value.filterNot { it.userId == userId }
    }

    /**
     * The power_level_content_override of a new group (ADR 0210, bipe-chat/bipe#879; registries/dm-power-levels.json
     * groupEvents): every member may set their own assistant marker (chat.bipe.assistant at 0). Synapse replaces its
     * whole default events map with the override's, so GROUP_EVENTS repeats the Synapse defaults. users stays empty,
     * so the creator keeps the server's default level; other fields keep the preset.
     */
    private fun groupPowerLevels() = PowerLevels(
        usersDefault = null,
        eventsDefault = null,
        stateDefault = null,
        ban = null,
        kick = null,
        redact = null,
        invite = null,
        notifications = null,
        users = emptyMap(),
        events = BipeDmPowerLevels.GROUP_EVENTS,
    )

    /** Action create. onCreated gets the new room ID and whether every invite went out. */
    fun create(onCreated: (roomId: String, allInvited: Boolean) -> Unit) {
        if (!canCreate(_name.value, _phase.value)) return
        _phase.value = Phase.CREATING
        scope.launch {
            val result = runCatching {
                val avatar = _photo.value?.let { session.client.uploadMedia(it.mimeType, it.bytes, null) }
                val roomId = session.client.createRoom(
                    CreateRoomParameters(
                        name = _name.value.trim(),
                        topic = null,
                        isEncrypted = true,
                        isDirect = false,
                        visibility = RoomVisibility.Private,
                        preset = RoomPreset.PRIVATE_CHAT,
                        invite = null,
                        avatar = avatar,
                        powerLevelContentOverride = groupPowerLevels(),
                    ),
                )
                // The room reaches the store with the next sync.
                val room = runCatching { session.client.getRoom(roomId) }.getOrNull()
                    ?: runCatching { session.client.awaitRoomRemoteEcho(roomId) }.getOrNull()
                var allInvited = room != null || _people.value.isEmpty()
                if (room != null) {
                    // One call each, so one refused invite does not stop the group.
                    for (person in _people.value) {
                        if (runCatching { room.inviteUserById(person.userId) }.isFailure) allInvited = false
                    }
                }
                roomId to allInvited
            }
            result.onSuccess { (roomId, allInvited) ->
                _phase.value = Phase.EDITING
                onCreated(roomId, allInvited)
            }.onFailure {
                _phase.value = Phase.FAILED
            }
        }
    }
}
