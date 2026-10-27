// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.readAddressBook
import chat.bipe.shared.BipeHandle
import chat.bipe.shared.api.inviteConnections
import chat.bipe.shared.api.lookupContacts
import java.util.Locale

/**
 * The Bipe API part of group-invite-members: the invite pairs and address-book matches among the contacts, and the
 * exact @ lookup. The DM partners and group members come from the SDK (GroupPeople.contacts).
 */
internal class GroupInviteLookup(private val context: Context, private val people: PeopleService) {

    /**
     * Invite pairs (GET /v1/me/invite-connections, ADR 0034) and, when contacts access is given already, the
     * address-book matches (POST /v1/contacts/lookup, ADR 0015). This screen never asks for contacts access.
     * A failed call leaves its people out.
     */
    suspend fun moreContacts(except: Set<String>): List<Person> {
        val names = linkedMapOf<String, String?>()
        val entries = mutableMapOf<String, String>()
        var lookedUp = false
        runCatching { people.withToken { people.api.inviteConnections(it) } }.getOrNull()?.forEach { names[it] = null }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val book = readAddressBook(context)
            for (chunk in book.keys.toList().chunked(LOOKUP_CHUNK)) {
                val found = runCatching { people.withToken { people.api.lookupContacts(chunk, it) } }.getOrNull() ?: break
                lookedUp = true
                found.forEach { match ->
                    val entry = book[match.phone] ?: return@forEach
                    names[match.userId] = entry.name
                    entries[match.userId] = entry.id
                }
            }
        }
        people.rememberContactNames(names.mapNotNull { (id, name) -> name?.let { id to it } }.toMap())
        if (lookedUp) people.rememberContactEntries(entries)
        return names.filterKeys { it != people.session.userId && it !in except }.map { (userId, name) ->
            runCatching { people.person(userId, contactName = name) }.getOrNull()
                ?.let { Person(it.userId, it.displayName, it.avatarUrl, it.handle) }
                ?: Person.of(userId, name, null)
        }
    }

    /** GET /v1/handles/{handle}: the holder of the @, or null when nobody holds it. Throws when the lookup fails. */
    suspend fun holder(handle: String): Person? {
        val userId = people.handles.userId(forHandle = handle) ?: return null
        val person = runCatching { people.person(userId) }.getOrNull()
        return person?.let { Person(it.userId, it.displayName, it.avatarUrl, it.handle) } ?: Person.of(userId, null, null).copy(handle = handle)
    }

    companion object {
        private const val LOOKUP_CHUNK = 500

        /** The handle in query when it is a valid @ (registries/handles.json), else null. */
        fun handle(query: String): String? =
            BipeHandle.sanitize(query).lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && BipeHandle.localProblem(it) == null }
    }
}
