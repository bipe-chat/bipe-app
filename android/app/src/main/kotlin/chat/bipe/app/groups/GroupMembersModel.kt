// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import chat.bipe.app.account.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMember
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate
import org.matrix.rustcomponents.sdk.suggestedPowerLevelForRole
import uniffi.matrix_sdk.RoomMemberRole
import uniffi.ruma_events.StateEventType

/** Type GroupMember of spec group-members. */
data class GroupMember(
    val person: Person,
    val membership: Membership,
    val isAdmin: Boolean,
    val isYou: Boolean,
    /** A creator (room version 12) has no role actions. */
    val isCreator: Boolean,
    val level: Long,
) {
    enum class Membership { JOINED, INVITED, BANNED }
}

/** Spec group-members (bipe-shared product/screens/group-members.json). */
class GroupMembersModel(
    val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
) {
    /** The rows of the action sheet (group-members notes). */
    enum class MemberAction { VIEW_PROFILE, MAKE_ADMIN, REMOVE_ADMIN, REMOVE, BAN, UNBAN, CANCEL_INVITE }

    data class Rights(val canInvite: Boolean, val canKick: Boolean, val canBan: Boolean, val canChangeRoles: Boolean)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _joined = MutableStateFlow<List<GroupMember>>(emptyList())
    val joined: StateFlow<List<GroupMember>> = _joined.asStateFlow()
    private val _invited = MutableStateFlow<List<GroupMember>>(emptyList())
    val invited: StateFlow<List<GroupMember>> = _invited.asStateFlow()
    private val _banned = MutableStateFlow<List<GroupMember>>(emptyList())
    /** State banned (bipe-chat/bipe#675): shown only when canBan. */
    val banned: StateFlow<List<GroupMember>> = _banned.asStateFlow()
    private val _rights = MutableStateFlow(Rights(false, false, false, false))
    val rights: StateFlow<Rights> = _rights.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private var all: List<GroupMember> = emptyList()
    private var ownLevel = 0L

    private fun room(): Room? = runCatching { session.client.getRoom(roomId) }.getOrNull()

    /** Action appear: the cached list at once, then the list from the server. */
    fun appear() {
        val room = room() ?: return
        scope.launch {
            runCatching { room.getPowerLevels() }.getOrNull()?.let { levels ->
                _rights.value = Rights(
                    canInvite = levels.canOwnUserInvite(),
                    canKick = levels.canOwnUserKick(),
                    canBan = levels.canOwnUserBan(),
                    canChangeRoles = levels.canOwnUserSendState(StateEventType.RoomPowerLevels),
                )
            }
            GroupPeople.members(room, cachedOnly = true)?.takeIf { it.isNotEmpty() }?.let {
                applyMembers(it)
                _loading.value = false
            }
            GroupPeople.members(room)?.let(::applyMembers)
            _loading.value = false
        }
    }

    /** Action setQuery. */
    fun setQuery(query: String) {
        _query.value = query
        rebuild()
    }

    /** The rows of the action sheet of a person, each only when allowed. */
    fun actions(member: GroupMember): List<MemberAction> {
        if (member.isYou) return emptyList()
        val rights = _rights.value
        // VIEW_PROFILE joins the list with the profile screen (bipe-chat/bipe#567).
        val actions = mutableListOf<MemberAction>()
        when (member.membership) {
            GroupMember.Membership.JOINED -> {
                if (rights.canChangeRoles && !member.isCreator && member.level < ownLevel) {
                    actions += if (member.isAdmin) MemberAction.REMOVE_ADMIN else MemberAction.MAKE_ADMIN
                }
                if (rights.canKick) actions += MemberAction.REMOVE
                if (rights.canBan) actions += MemberAction.BAN
            }
            GroupMember.Membership.INVITED -> if (rights.canKick) actions += MemberAction.CANCEL_INVITE
            GroupMember.Membership.BANNED -> if (rights.canBan) actions += MemberAction.UNBAN
        }
        return actions
    }

    /** Actions makeAdmin, removeAdmin, remove, ban and cancelInvite. Remove and ban are confirmed by the screen first. */
    fun perform(action: MemberAction, member: GroupMember) {
        val room = room() ?: return
        val userId = member.person.userId
        scope.launch {
            runCatching {
                when (action) {
                    MemberAction.VIEW_PROFILE -> return@launch
                    MemberAction.MAKE_ADMIN -> setLevel(room, userId, RoomMemberRole.ADMINISTRATOR)
                    MemberAction.REMOVE_ADMIN -> setLevel(room, userId, RoomMemberRole.USER)
                    MemberAction.REMOVE, MemberAction.CANCEL_INVITE -> room.kickUser(userId, null)
                    MemberAction.BAN -> room.banUser(userId, null)
                    MemberAction.UNBAN -> room.unbanUser(userId, null)
                }
            }.onSuccess {
                GroupPeople.members(room)?.let(::applyMembers)
            }.onFailure {
                _error.value = true
            }
        }
    }

    fun dismissError() {
        _error.value = false
    }

    private suspend fun setLevel(room: Room, userId: String, role: RoomMemberRole) {
        val level = suggestedPowerLevelForRole(role) as? PowerLevel.Value ?: return
        room.updatePowerLevelsForUsers(listOf(UserPowerLevelUpdate(userId, level.value)))
    }

    private fun applyMembers(members: List<RoomMember>) {
        members.firstOrNull { it.userId == session.userId }?.let { ownLevel = GroupPeople.level(it.powerLevel) }
        all = members.mapNotNull { member ->
            val membership = when (member.membership) {
                is MembershipState.Join -> GroupMember.Membership.JOINED
                is MembershipState.Invite -> GroupMember.Membership.INVITED
                is MembershipState.Ban -> GroupMember.Membership.BANNED
                else -> return@mapNotNull null
            }
            GroupMember(
                person = Person.of(member),
                membership = membership,
                isAdmin = GroupPeople.isAdmin(member),
                isYou = member.userId == session.userId,
                isCreator = member.suggestedRoleForPowerLevel == RoomMemberRole.CREATOR,
                level = GroupPeople.level(member.powerLevel),
            )
        }
        rebuild()
    }

    private fun rebuild() {
        val query = _query.value
        val visible = all.filter { it.person.matches(query) }
        val byName = compareBy(GroupPeople.byName) { member: GroupMember -> member.person.displayName }
        _joined.value = visible.filter { it.membership == GroupMember.Membership.JOINED }
            .sortedWith(compareByDescending<GroupMember> { it.isAdmin }.then(byName))
        _invited.value = visible.filter { it.membership == GroupMember.Membership.INVITED }.sortedWith(byName)
        _banned.value = if (_rights.value.canBan) visible.filter { it.membership == GroupMember.Membership.BANNED }.sortedWith(byName) else emptyList()
    }
}
