// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

object BipeChatRowName {
    /** Missing heroes must not discard an explicit/computed SDK room name. */
    fun resolve(roomId: String, sdkName: String?, peerName: String?, peerId: String?, unnamed: String): String {
        for (value in listOf(sdkName, peerName)) {
            val name = value?.trim()
            if (!name.isNullOrEmpty() && name != roomId && name != peerId) return name
        }
        return peerId?.removePrefix("@")?.substringBefore(":")?.takeIf { it.isNotEmpty() } ?: unnamed
    }
}
