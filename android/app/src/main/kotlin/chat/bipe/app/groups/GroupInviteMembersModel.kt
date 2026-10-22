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

/** Type PersonPick of spec group-invite-members. */
data class PersonPick(val person: Person, val status: Status) {
    enum class Status { SELECTABLE, SELECTED, MEMBER, INVITED, REFUSED, FAILED }
}

/** Spec group-invite-members (bipe-shared product/screens/group-invite-members.json). */
class GroupInviteMembersModel(
    private val session: AccountSession,
    val mode: Mode,
    private val scope: CoroutineScope,
) {
    sealed interface Mode {
        /** From group-create, with the people it already has. Nothing is sent. */
        data class Pick(val people: List<Person>) : Mode

        /** For an existing group. */
        data class Invite(val roomId: String) : Mode
    }

    enum class Phase { CHOOSING, INVITING, DONE }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _contacts = MutableStateFlow<List<PersonPick>>(emptyList())
    val contacts: StateFlow<List<PersonPick>> = _contacts.asStateFlow()
    private val _phase = MutableStateFlow(Phase.CHOOSING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    private var people: List<Person> = (mode as? Mode.Pick)?.people.orEmpty()

    /** The people picked, in the order picked. */
    private val selected = people.map { it.userId }.toMutableList()
    private val memberStatus = mutableMapOf<String, PersonPick.Status>()
    private val results = mutableMapOf<String, PersonPick.Status>()

    fun appear() {
        if (!_loading.value) return
        scope.launch {
            val contacts = GroupPeople.contacts(session.client, session.userId).toMutableList()
            // People picked before stay on the list even when they are not contacts.
            people.filter { picked -> contacts.none { it.userId == picked.userId } }.forEach { contacts += it }
            people = contacts
            val invite = mode as? Mode.Invite
            if (invite != null) {
                val room = runCatching { session.client.getRoom(invite.roomId) }.getOrNull()
                room?.let { GroupPeople.members(it) }?.forEach { member ->
                    when (member.membership) {
                        is MembershipState.Join -> memberStatus[member.userId] = PersonPick.Status.MEMBER
                        is MembershipState.Invite -> memberStatus[member.userId] = PersonPick.Status.INVITED
                        else -> Unit
                    }
                }
            }
            _loading.value = false
            rebuild()
        }
    }

    /** Action setQuery: filters contacts at once. */
    fun setQuery(query: String) {
        _query.value = query
        rebuild()
    }

    /** Action toggle. */
    fun toggle(userId: String) {
        if (_phase.value == Phase.INVITING) return
        when (status(userId)) {
            PersonPick.Status.MEMBER, PersonPick.Status.INVITED -> return
            // Try again: the person is picked once more.
            PersonPick.Status.REFUSED, PersonPick.Status.FAILED -> results.remove(userId)
            PersonPick.Status.SELECTABLE, PersonPick.Status.SELECTED -> Unit
        }
        _phase.value = Phase.CHOOSING
        if (!selected.remove(userId)) selected += userId
        rebuild()
    }

    /** The selected people, in the order picked (pick mode). */
    fun selectedPeople(): List<Person> = selected.mapNotNull { userId -> people.firstOrNull { it.userId == userId } }

    /** Action invite, invite mode: one call per person; each row shows its result. onAllSent runs when all succeed. */
    fun invite(onAllSent: () -> Unit) {
        val invite = mode as? Mode.Invite ?: return
        if (_phase.value != Phase.CHOOSING || selected.isEmpty()) return
        val room = runCatching { session.client.getRoom(invite.roomId) }.getOrNull() ?: return
        _phase.value = Phase.INVITING
        scope.launch {
            var allSent = true
            for (userId in selected.toList()) {
                val error = runCatching { room.inviteUserById(userId) }.exceptionOrNull()
                when (InviteOutcome.of(error)) {
                    InviteOutcome.SENT -> memberStatus[userId] = PersonPick.Status.INVITED
                    InviteOutcome.REFUSED -> {
                        results[userId] = PersonPick.Status.REFUSED
                        allSent = false
                    }
                    InviteOutcome.FAILED -> {
                        results[userId] = PersonPick.Status.FAILED
                        allSent = false
                    }
                }
                rebuild()
            }
            selected.clear()
            _phase.value = Phase.DONE
            rebuild()
            if (allSent) onAllSent()
        }
    }

    private fun status(userId: String): PersonPick.Status =
        results[userId] ?: memberStatus[userId] ?: if (userId in selected) PersonPick.Status.SELECTED else PersonPick.Status.SELECTABLE

    private fun rebuild() {
        val query = _query.value
        _contacts.value = people
            .filter { it.matches(query) || results.containsKey(it.userId) }
            .map { PersonPick(it, status(it.userId)) }
        _selectedCount.value = selected.size
    }
}
