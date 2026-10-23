// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import java.text.Collator
import java.text.Normalizer
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMember
import uniffi.matrix_sdk.RoomMemberRole

/** Type Person of spec group-create. */
data class Person(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val handle: String? = null,
) {
    /** Filter of group-invite-members and group-members: by name or @, ignoring case and accents. */
    fun matches(query: String): Boolean {
        val q = fold(query.trim().trimStart('@'))
        if (q.isEmpty()) return true
        return fold(displayName).contains(q) || handle?.let { fold(it).contains(q) } == true || fold(userId).contains(q)
    }

    companion object {
        fun of(userId: String, displayName: String?, avatarUrl: String?): Person =
            Person(userId, displayName?.takeIf { it.isNotEmpty() } ?: chat.bipe.app.people.Person.localpart(userId), avatarUrl)

        fun of(member: RoomMember): Person = of(member.userId, member.displayName, member.avatarUrl)

        private fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}

object GroupPeople {
    private const val CHUNK_SIZE = 200u

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    val byName: Comparator<String> = Comparator { a, b -> collator.compare(a, b) }

    /** Every member of a room, read through the members iterator until it is empty. */
    suspend fun members(room: Room, cachedOnly: Boolean = false): List<RoomMember>? {
        val iterator = runCatching { if (cachedOnly) room.membersNoSync() else room.members() }.getOrNull() ?: return null
        val members = mutableListOf<RoomMember>()
        while (true) {
            val chunk = iterator.nextChunk(CHUNK_SIZE)
            if (chunk.isNullOrEmpty()) break
            members += chunk
        }
        iterator.destroy()
        return members
    }

    /**
     * The user's contacts (spec group-invite-members notes): the other person of each DM and the members of the
     * user's groups. Invite pairs and address-book matches come with the Bipe API client.
     */
    suspend fun contacts(client: Client, ownUserId: String): List<Person> {
        val people = linkedMapOf<String, Person>()
        for (room in client.rooms()) {
            val info = runCatching { room.roomInfo() }.getOrNull() ?: continue
            if (info.membership != Membership.JOINED || info.isSpace) continue
            if (info.isDm) {
                info.heroes.filter { it.userId != ownUserId }.forEach { hero ->
                    people.getOrPut(hero.userId) { Person.of(hero.userId, hero.displayName, hero.avatarUrl) }
                }
            } else {
                members(room, cachedOnly = true)
                    ?.filter { it.membership is MembershipState.Join && it.userId != ownUserId && !it.isServiceMember }
                    ?.forEach { member ->
                        val known = people[member.userId]
                        if (known == null || known.displayName == member.userId) people[member.userId] = Person.of(member)
                    }
            }
        }
        return people.values.sortedWith(compareBy(byName) { it.displayName })
    }

    fun level(powerLevel: PowerLevel): Long = when (powerLevel) {
        PowerLevel.Infinite -> Long.MAX_VALUE
        is PowerLevel.Value -> powerLevel.value
    }

    fun isAdmin(member: RoomMember): Boolean =
        member.suggestedRoleForPowerLevel == RoomMemberRole.CREATOR || member.suggestedRoleForPowerLevel == RoomMemberRole.ADMINISTRATOR
}

/** How an invite went (group-invite-members PersonPick status refused and failed). */
enum class InviteOutcome {
    SENT,
    REFUSED,
    FAILED,
    ;

    companion object {
        /** A refused invite is the server's who-can-message module (ADR 0038): the spam checker answers M_FORBIDDEN. */
        fun of(error: Throwable?): InviteOutcome = when {
            error == null -> SENT
            error is ClientException.MatrixApi && error.code == "M_FORBIDDEN" -> REFUSED
            else -> FAILED
        }
    }
}
