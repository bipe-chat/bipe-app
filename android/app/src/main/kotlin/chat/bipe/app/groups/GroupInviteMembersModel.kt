// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.readAddressBook
import chat.bipe.shared.BipeHandle
import chat.bipe.shared.api.inviteConnections
import chat.bipe.shared.api.lookupContacts
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    private val service: PeopleService?,
    private val context: Context,
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
    private val _handleMatch = MutableStateFlow<PersonPick?>(null)
    val handleMatch = _handleMatch.asStateFlow()
    private val _handleNotFound = MutableStateFlow(false)
    val handleNotFound = _handleNotFound.asStateFlow()
    private val _lookupFailed = MutableStateFlow(false)
    val lookupFailed = _lookupFailed.asStateFlow()
    private val _lookingUp = MutableStateFlow(false)
    val lookingUp = _lookingUp.asStateFlow()
    private var lookupJob: Job? = null
    private var lookupVersion = 0L
    private var handlePerson: Person? = null
    private val lookupOnlyIds = mutableSetOf<String>()
    private val _enrichingContacts = MutableStateFlow(false)
    val enrichingContacts = _enrichingContacts.asStateFlow()
    private val _contactsFailed = MutableStateFlow(false)
    val contactsFailed = _contactsFailed.asStateFlow()
    private var contactsJob: Job? = null
    private var contactsVersion = 0L

    private var people: List<Person> = (mode as? Mode.Pick)?.people.orEmpty()

    /** The people picked, in the order picked. */
    private val selected = people.map { it.userId }.toMutableList()
    private val memberStatus = mutableMapOf<String, PersonPick.Status>()
    private val results = mutableMapOf<String, PersonPick.Status>()

    fun appear() {
        if (!_loading.value) return
        scope.launch {
            val contacts = GroupPeople.contacts(session.client, session.userId).toMutableList()
            lookupOnlyIds.removeAll(contacts.map { it.userId }.toSet())
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
            selected.removeAll { memberStatus[it] == PersonPick.Status.MEMBER || memberStatus[it] == PersonPick.Status.INVITED }
            setQuery(_query.value)
            refreshContacts()
        }
    }

    /** Filter immediately; valid exact @ queries use the existing handle endpoint after a short pause. */
    fun setQuery(query: String) {
        _query.value = query
        lookupJob?.cancel()
        val version = ++lookupVersion
        handlePerson = null
        _handleNotFound.value = false
        _lookupFailed.value = false
        _lookingUp.value = false
        rebuild()
        val input = query.trim()
        val peopleService = service ?: return
        if (!input.startsWith('@')) return
        val handle = input.removePrefix("@").lowercase(Locale.ROOT)
        if (BipeHandle.localProblem(handle) != null) return
        _lookingUp.value = true
        lookupJob = scope.launch {
            try {
                delay(250)
                val id = peopleService.handles.userId(handle)
                coroutineContext.ensureActive()
                if (version != lookupVersion) return@launch
                if (id == null || id == session.userId || peopleService.isBlocked(id)) {
                    _lookingUp.value = false
                    _handleNotFound.value = true
                    return@launch
                }
                val loaded = runCatching { peopleService.person(id) }.getOrNull()
                coroutineContext.ensureActive()
                if (version != lookupVersion) return@launch
                val person = loaded?.let { Person(it.userId, it.displayName, it.avatarUrl, it.handle ?: handle) }
                    ?: Person(id, "@$handle", null, handle)
                if (people.none { it.userId == id }) { people = people + person; lookupOnlyIds += id }
                else people = people.map { if (it.userId == id && it.handle == null) it.copy(handle = handle) else it }
                handlePerson = people.firstOrNull { it.userId == id } ?: person
                _lookingUp.value = false
                rebuild()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (version == lookupVersion) { _lookingUp.value = false; _lookupFailed.value = true }
            }
        }
    }

    fun close() {
        lookupJob?.cancel(); _lookingUp.value = false
        contactsJob?.cancel(); contactsJob = null; contactsVersion += 1; _enrichingContacts.value = false
    }

    /** Keep local room contacts usable. Add invite pairs and phone matches only with existing OS permission. */
    fun refreshContacts() {
        val peopleService = service ?: return
        if (contactsJob?.isActive == true) return
        val version = ++contactsVersion
        _enrichingContacts.value = true
        _contactsFailed.value = false
        contactsJob = scope.launch {
            try {
                try {
                    val ids = peopleService.withToken { peopleService.api.inviteConnections(it) }
                    val found = peopleService.people(ids.filter { it != session.userId && !peopleService.isBlocked(it) })
                    coroutineContext.ensureActive()
                    if (version != contactsVersion) return@launch
                    mergeContacts(found.map { Person(it.userId, it.displayName, it.avatarUrl, it.handle) }, preferLoadedNames = false)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { if (version == contactsVersion) _contactsFailed.value = true }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    val names = readAddressBook(context)
                    val found = linkedMapOf<String, String>()
                    names.keys.toList().chunked(500).forEach { phones ->
                        peopleService.withToken { peopleService.api.lookupContacts(phones, it) }.forEach { match ->
                            if (match.userId != session.userId && match.userId !in found) found[match.userId] = names[match.phone].orEmpty()
                        }
                    }
                    val matches = found.mapNotNull { (id, name) ->
                        if (peopleService.isBlocked(id)) null
                        else {
                            val loaded = runCatching { peopleService.person(id, contactName = name) }.getOrNull()
                            coroutineContext.ensureActive()
                            loaded?.let { Person(it.userId, it.displayName, it.avatarUrl, it.handle) }
                                ?: Person.of(id, name, null)
                        }
                    }
                    coroutineContext.ensureActive()
                    if (version != contactsVersion) return@launch
                    mergeContacts(matches, preferLoadedNames = true)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (version == contactsVersion) _contactsFailed.value = true }
            finally { if (version == contactsVersion) { _enrichingContacts.value = false; contactsJob = null } }
        }
    }

    private fun mergeContacts(incoming: List<Person>, preferLoadedNames: Boolean) {
        val combined = people.associateByTo(linkedMapOf()) { it.userId }
        for (person in incoming) {
            if (person.userId == session.userId || service?.isBlocked(person.userId) == true) continue
            lookupOnlyIds -= person.userId
            val old = combined[person.userId]
            combined[person.userId] = if (old == null) person else old.copy(
                displayName = if (preferLoadedNames) person.displayName else old.displayName,
                handle = person.handle ?: old.handle, avatarUrl = person.avatarUrl ?: old.avatarUrl,
            )
        }
        people = combined.values.sortedWith(compareBy(GroupPeople.byName) { it.displayName })
        handlePerson = handlePerson?.let { current -> people.firstOrNull { it.userId == current.userId } }
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
        selected.removeAll { it == session.userId || service?.isBlocked(it) == true }
        _contacts.value = people
            .filter { it.userId != session.userId && service?.isBlocked(it.userId) != true }
            .filter { it.userId !in lookupOnlyIds || it.userId in selected || results.containsKey(it.userId) }
            .filter { it.userId != handlePerson?.userId }
            .filter { it.matches(query) || results.containsKey(it.userId) }
            .map { PersonPick(it, status(it.userId)) }
        _selectedCount.value = selected.size
        _handleMatch.value = handlePerson?.let { PersonPick(it, status(it.userId)) }
    }
}
