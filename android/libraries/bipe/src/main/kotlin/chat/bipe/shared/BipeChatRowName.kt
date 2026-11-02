// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

object BipeChatRowName {
    /**
     * Missing heroes must not discard an explicit/computed SDK room name.
     * A DM passes its m.room.name (RoomInfo.rawName) as [dmRoomName]: a DM never shows it (ADR 0141).
     * After the names: the peer's @handle, else [unnamed]. Never the Matrix ID or its localpart (People names).
     * A group without m.room.name passes its heroes (user ID, display name) as [groupHeroes]: the row names them by the
     * people-names rule, each without a name as [noName].
     */
    fun resolve(
        roomId: String, sdkName: String?, peerName: String?, peerId: String?, unnamed: String,
        dmRoomName: String? = null, peerHandle: String? = null, groupHeroes: List<Pair<String, String?>>? = null,
        noName: String = unnamed,
    ): String {
        // A group without m.room.name: the SDK builds the name from display names, which can be localparts.
        if (!groupHeroes.isNullOrEmpty()) return groupHeroes.joinToString(", ") { (id, name) -> person(id, null, name, null, noName) }
        val roomName = dmRoomName?.trim()
        val name = if (!roomName.isNullOrEmpty() && sdkName?.trim() == roomName) null else sdkName
        for (value in listOf(name, peerName)) {
            val name = value?.trim()
            if (name.isNullOrEmpty() || name == roomId || isMatrixId(name)) continue
            if (peerId != null && name == localpart(peerId)) continue
            return name
        }
        val handle = peerHandle ?: peerId?.let(BipeHandleCache::handle)
        return handle?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: unnamed
    }

    /**
     * Spec People names (bipe-shared product/screens README, MZ 2 November 2026): the saved contact name, then the
     * display name when it is a real name, then the @handle, then [placeholder] (bipe_person_no_name). Never the Matrix
     * ID or its localpart.
     */
    fun person(userId: String, contactName: String?, displayName: String?, handle: String?, placeholder: String): String {
        contactName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        realName(displayName, userId)?.let { return it }
        return (handle ?: BipeHandleCache.handle(userId))?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: placeholder
    }

    /**
     * The display name when it is a name: not empty, not a Matrix ID, not the localpart of [userId]. Synapse starts a
     * new account's display name as its localpart, and only Profile edit sets a real one.
     */
    fun realName(displayName: String?, userId: String): String? {
        val name = displayName?.trim()
        if (name.isNullOrEmpty() || name == userId || isMatrixId(name) || name == localpart(userId)) return null
        return name
    }

    /** The localpart of a Matrix ID, for comparison only: never a name to show. */
    internal fun localpart(userId: String): String = userId.removePrefix("@").substringBefore(':')

    /** "@localpart:server" or "!room:server": text that is an ID, not a name. */
    internal fun isMatrixId(text: String): Boolean =
        (text.startsWith("@") || text.startsWith("!")) && ':' in text && ' ' !in text
}
