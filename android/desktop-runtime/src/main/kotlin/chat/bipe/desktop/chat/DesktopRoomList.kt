// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.toVectorDiff
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.account.NativeResource
import chat.bipe.desktop.account.TaskResource
import chat.bipe.shared.BipeFavouritesOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.TimelineItemContent

class DesktopRoomList private constructor(
    private val runtime: MatrixAccountRuntime,
    private val group: AccountTaskGroup,
    private val labels: ChatLabels,
) : AccountResource {
    private val mutableRows = MutableStateFlow<LoadPhase<List<ChatRow>>>(LoadPhase.Loading)
    val rows = mutableRows.asStateFlow()
    private val mutableOrder = MutableStateFlow<List<String>>(emptyList())
    val favouriteOrder = mutableOrder.asStateFlow()
    private val entries = mutableListOf<RoomListEntriesWithDynamicAdaptersResult>()

    fun loadMore() = group.launch {
        for (entry in entries) {
            val controller = entry.controller()
            try { controller.addOnePage() } finally { controller.destroy() }
        }
    }

    override suspend fun close() = group.close()

    private suspend fun start() {
        val sync = runtime.startSync()
        val service = sync.roomListService()
        group.own(NativeResource { service.destroy() })
        val list = service.allRooms()
        group.own(NativeResource { list.destroy() })
        // The dedicated favourite stream finds favourites outside the current page, as on Mac/Android.
        val snapshots = listOf(SnapshotVector<ChatRow>(), SnapshotVector<ChatRow>())
        val snapshotLock = Mutex()
        for (index in snapshots.indices) {
            val changes = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED, onUndeliveredElement = ::dispose)
            group.own(NativeResource { changes.cancel() })
            val entry = list.entriesWithDynamicAdapters(100u, object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    if (changes.trySend(roomEntriesUpdate).isFailure) dispose(roomEntriesUpdate)
                }
            })
            entries += entry
            group.own(NativeResource { entry.destroy() })
            val controller = entry.controller()
            try {
                val filters = mutableListOf(RoomListEntriesDynamicFilterKind.Joined,
                    RoomListEntriesDynamicFilterKind.NonSpace, RoomListEntriesDynamicFilterKind.DeduplicateVersions)
                if (index == 1) filters += RoomListEntriesDynamicFilterKind.Favourite
                controller.setFilter(RoomListEntriesDynamicFilterKind.All(filters))
            } finally { controller.destroy() }
            group.own(TaskResource(entry.entriesStream()))
            group.launch {
                try {
                    for (batch in changes) {
                        val converted = mutableListOf<chat.bipe.app.common.VectorDiff<ChatRow?>>()
                        try {
                            for (diff in batch) converted += diff.toVectorDiff().snapshot { summary(it) }
                        } finally { dispose(batch) }
                        snapshotLock.withLock {
                            converted.forEach { snapshots[index].apply(it) }
                            val current = snapshots.flatMap { it.visible() }.distinctBy { it.id }
                            group.publish { mutableRows.value = LoadPhase.Ready(current) }
                        }
                        val order = BipeFavouritesOrder.parse(runtime.client.accountData(BipeFavouritesOrder.EVENT_TYPE))
                        group.publish { mutableOrder.value = order }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { group.publish { mutableRows.value = LoadPhase.Failed } }
            }
        }
    }

    private suspend fun summary(room: Room): ChatRow? {
        val info = room.roomInfo()
        if (info.membership != Membership.JOINED || info.isSpace) return null
        val own = runtime.client.userId()
        val heroes = info.heroes.filter { it.userId != own }
        val peer = heroes.firstOrNull()
        val name = if (info.isDm) realName(peer?.displayName, peer?.userId, labels.noName)
            else info.rawName?.takeIf { it.isNotBlank() } ?: heroes.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { realName(it.displayName, it.userId, labels.noName) } ?: labels.unnamed
        var preview: String? = null
        var timestamp: Long? = null
        when (val latest = room.latestEvent()) {
            is LatestEventValue.Remote -> { preview = preview(latest.content); timestamp = latest.timestamp.toLong() }
            is LatestEventValue.Local -> { preview = preview(latest.content); timestamp = latest.timestamp.toLong() }
            else -> Unit
        }
        val draft = try { room.loadComposerDraft(null)?.takeIf {
            it.draftType is ComposerDraftType.NewMessage && it.attachments.isEmpty()
        }?.plainText?.takeIf { it.isNotBlank() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // The composer presents a retryable load failure; rows keep their last message.
        return ChatRow(info.id, name, if (info.isDm) peer?.avatarUrl else info.avatarUrl,
            heroes.take(3).map { ChatAvatar(realName(it.displayName, it.userId, labels.noName), it.avatarUrl) },
            info.isDm, info.isFavourite, info.isLowPriority, info.numUnreadMessages.toLong(), preview, timestamp, draft)
    }

    private fun preview(content: TimelineItemContent): String? = when (val kind = (content as? TimelineItemContent.MsgLike)?.content?.kind) {
        is MsgLikeKind.Message -> kind.content.body
        is MsgLikeKind.UnableToDecrypt -> labels.undecryptable
        null -> null
        else -> labels.unsupported
    }

    companion object {
        suspend fun open(runtime: MatrixAccountRuntime, labels: ChatLabels): DesktopRoomList {
            val group = AccountTaskGroup.create(runtime.lifetime)
            val model = DesktopRoomList(runtime, group, labels)
            try { group.async { model.start() }.await(); return model }
            catch (error: Exception) { group.close(); throw error }
        }

        private fun dispose(updates: List<RoomListEntriesUpdate>) {
            updates.forEach { it.toVectorDiff().forEachValue { room -> room.destroy() } }
        }
    }
}
