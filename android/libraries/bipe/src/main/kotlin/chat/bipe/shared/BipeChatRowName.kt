// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

object BipeChatRowName {
    /**
     * Missing heroes must not discard an explicit/computed SDK room name.
     * A DM passes its m.room.name (RoomInfo.rawName) as [dmRoomName]: a DM never shows it (ADR 0141).
     */
    fun resolve(roomId: String, sdkName: String?, peerName: String?, peerId: String?, unnamed: String, dmRoomName: String? = null): String {
        val roomName = dmRoomName?.trim()
        val name = if (!roomName.isNullOrEmpty() && sdkName?.trim() == roomName) null else sdkName
        for (value in listOf(name, peerName)) {
            val name = value?.trim()
            if (!name.isNullOrEmpty() && name != roomId && name != peerId) return name
        }
        return peerId?.removePrefix("@")?.substringBefore(":")?.takeIf { it.isNotEmpty() } ?: unnamed
    }
}
