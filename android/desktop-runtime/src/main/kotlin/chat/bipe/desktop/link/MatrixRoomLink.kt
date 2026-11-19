// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.MatrixAccountRuntime
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.MatrixId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.parseMatrixEntityFrom

sealed interface MatrixRoomLinkResult {
    data class Joined(val roomId: String, val isDm: Boolean, val eventId: String?) : MatrixRoomLinkResult
    data class Preview(
        val roomId: String,
        val name: String?,
        val avatarUrl: String?,
        val topic: String?,
        val memberCount: ULong,
        val isDm: Boolean,
        val access: Access,
    ) : MatrixRoomLinkResult {
        enum class Access { JOIN, KNOCK, INVITE_ONLY, KNOCK_SENT }
    }
    data object NotFound : MatrixRoomLinkResult
    data object Failed : MatrixRoomLinkResult
}

class MatrixRoomLink private constructor(
    private val idOrAlias: String,
    private val isAlias: Boolean,
    private val eventId: String?,
    private val via: List<String>,
) {
    /** The local SDK store also knows about rooms outside the sidebar's current page. */
    suspend fun open(runtime: MatrixAccountRuntime): MatrixRoomLinkResult = accountRequest(runtime) { client ->
        if (!isAlias) localJoined(client, idOrAlias)?.let { return@accountRequest it }
        val preview = if (isAlias) client.getRoomPreviewFromRoomAlias(idOrAlias)
            else client.getRoomPreviewFromRoomId(idOrAlias, via)
        try {
            val info = preview.info()
            if (info.roomType is RoomType.Space) return@accountRequest MatrixRoomLinkResult.NotFound
            if (info.membership == Membership.JOINED) {
                return@accountRequest localJoined(client, info.roomId)
                    ?: MatrixRoomLinkResult.Joined(info.roomId, info.isDirect == true, eventId)
            }
            MatrixRoomLinkResult.Preview(info.roomId, info.name?.takeIf { it.isNotBlank() },
                info.avatarUrl, info.topic?.takeIf { it.isNotBlank() }, info.numJoinedMembers,
                info.isDirect == true, roomLinkAccess(info.membership, info.joinRule))
        } finally { preview.destroy() }
    }

    /** Bind submission to the room shown in the preview, even if someone repoints an alias. */
    suspend fun submit(runtime: MatrixAccountRuntime, preview: MatrixRoomLinkResult.Preview): MatrixRoomLinkResult =
        accountRequest(runtime, onForbidden = { preview.copy(access = MatrixRoomLinkResult.Preview.Access.INVITE_ONLY) }) { client ->
            val access = preview.access
            if (access != MatrixRoomLinkResult.Preview.Access.JOIN && access != MatrixRoomLinkResult.Preview.Access.KNOCK) {
                return@accountRequest preview
            }
            localJoined(client, preview.roomId)?.let { return@accountRequest it }
            val servers = if (isAlias) (via + idOrAlias.substringAfter(':')).distinct() else via
            if (access == MatrixRoomLinkResult.Preview.Access.KNOCK) {
                val room = client.knock(preview.roomId, null, servers)
                room.destroy()
                preview.copy(access = MatrixRoomLinkResult.Preview.Access.KNOCK_SENT)
            } else {
                val room = client.joinRoomByIdOrAlias(preview.roomId, servers)
                try { MatrixRoomLinkResult.Joined(room.id(), preview.isDm, eventId) } finally { room.destroy() }
            }
        }

    private suspend fun localJoined(client: Client, roomId: String): MatrixRoomLinkResult? {
        val room = client.getRoom(roomId) ?: return null
        try {
            val info = room.roomInfo()
            try {
                if (info.isSpace) return MatrixRoomLinkResult.NotFound
                return if (info.membership == Membership.JOINED) MatrixRoomLinkResult.Joined(info.id, info.isDm, eventId) else null
            } finally { info.destroy() }
        } finally { room.destroy() }
    }

    private suspend fun accountRequest(
        runtime: MatrixAccountRuntime,
        onForbidden: () -> MatrixRoomLinkResult = { MatrixRoomLinkResult.NotFound },
        request: suspend (Client) -> MatrixRoomLinkResult,
    ): MatrixRoomLinkResult {
        val pending = runtime.lifetime.async { request(runtime.client) }
        return try { pending.await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ClientException.MatrixApi) {
            when (error.kind) {
                ErrorKind.NotFound -> MatrixRoomLinkResult.NotFound
                ErrorKind.Forbidden -> onForbidden()
                else -> MatrixRoomLinkResult.Failed
            }
        } catch (_: Exception) { MatrixRoomLinkResult.Failed }
        finally { pending.cancel() }
    }

    companion object {
        fun parse(url: String): MatrixRoomLink? {
            if (!url.startsWith("https://matrix.to/#/") && !url.startsWith("matrix:")) return null
            val entity = parseMatrixEntityFrom(url) ?: return null
            return when (val id = entity.id) {
                is MatrixId.Room -> MatrixRoomLink(id.id, false, null, entity.via)
                is MatrixId.RoomAlias -> MatrixRoomLink(id.alias, true, null, entity.via)
                is MatrixId.EventOnRoomId -> MatrixRoomLink(id.roomId, false, id.eventId, entity.via)
                is MatrixId.EventOnRoomAlias -> MatrixRoomLink(id.alias, true, id.eventId, entity.via)
                is MatrixId.User -> null
            }
        }
    }
}

internal fun roomLinkAccess(membership: Membership?, rule: JoinRule?): MatrixRoomLinkResult.Preview.Access =
    when {
        membership == Membership.KNOCKED -> MatrixRoomLinkResult.Preview.Access.KNOCK_SENT
        membership == Membership.BANNED || rule == JoinRule.Invite || rule == JoinRule.Private -> MatrixRoomLinkResult.Preview.Access.INVITE_ONLY
        rule == JoinRule.Knock || rule is JoinRule.KnockRestricted -> MatrixRoomLinkResult.Preview.Access.KNOCK
        else -> MatrixRoomLinkResult.Preview.Access.JOIN
    }
