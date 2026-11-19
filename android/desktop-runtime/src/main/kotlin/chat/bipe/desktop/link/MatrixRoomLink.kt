// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.MatrixAccountRuntime
import org.matrix.rustcomponents.sdk.MatrixId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.parseMatrixEntityFrom

data class MatrixRoomLink private constructor(val idOrAlias: String, val isAlias: Boolean, val eventId: String?) {
    data class JoinedRoom(val roomId: String, val isDm: Boolean, val eventId: String?)

    /** Resolve against this account only, even if the sidebar has not paged in the room. */
    suspend fun joinedRoom(runtime: MatrixAccountRuntime): JoinedRoom? {
        val lookup = runtime.lifetime.async {
            val roomId = if (isAlias) runtime.client.resolveRoomAlias(idOrAlias)?.roomId else idOrAlias
            val room = roomId?.let(runtime.client::getRoom) ?: return@async null
            try {
                val info = room.roomInfo()
                try {
                    if (info.membership == Membership.JOINED && !info.isSpace) JoinedRoom(info.id, info.isDm, eventId)
                    else null
                } finally { info.destroy() }
            } finally { room.destroy() }
        }
        try { return lookup.await() } finally { lookup.cancel() }
    }

    companion object {
        fun parse(url: String): MatrixRoomLink? {
            if (!url.startsWith("https://matrix.to/#/") && !url.startsWith("matrix:")) return null
            return when (val id = parseMatrixEntityFrom(url)?.id) {
                is MatrixId.Room -> MatrixRoomLink(id.id, false, null)
                is MatrixId.RoomAlias -> MatrixRoomLink(id.alias, true, null)
                is MatrixId.EventOnRoomId -> MatrixRoomLink(id.roomId, false, id.eventId)
                is MatrixId.EventOnRoomAlias -> MatrixRoomLink(id.alias, true, id.eventId)
                is MatrixId.User, null -> null
            }
        }
    }
}
