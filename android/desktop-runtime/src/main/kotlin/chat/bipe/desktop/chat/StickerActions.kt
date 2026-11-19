// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.stickers.*
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room

class StickerActions internal constructor(
    private val runtime: MatrixAccountRuntime,
    private val owner: AccountTaskGroup,
    private val room: () -> Room,
    private val row: (String) -> MessageRow?,
    private val threadRoot: String? = null,
) {
    data class Picker internal constructor(
        val packs: List<StickerPack>, val favourites: List<Sticker>, val recents: List<Sticker>,
    )
    private val writes get() = runtime.stickerWriteLock
    private var pendingRecents = emptyList<StickerRef>()

    private fun client(): StickerPackClient {
        val session = runtime.client.session()
        val uri = URI(session.homeserverUrl)
        check(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        return StickerPackClient(session.homeserverUrl, runtime.client.userId()) {
            owner.requireCurrent()
            runtime.client.session().accessToken
        }
    }

    suspend fun picker(): Picker {
        val task = owner.async {
            val packs = client().packs(room().id())
            val favourites = client().shortlist(BipeRegistries.STICKER_FAVOURITES_EVENT_TYPE)
            val recents = client().shortlist(BipeRegistries.STICKER_RECENTS_EVENT_TYPE)
            val pending = writes.withLock { pendingRecents }
            Picker(packs, StickerShortlist.resolve(favourites, packs),
                StickerShortlist.resolve((pending + recents).distinct() - favourites.toSet(), packs))
        }
        try { return task.await() } finally { task.cancel() }
    }

    suspend fun send(picker: Picker, sticker: Sticker) = owner.async {
        check(picker.packs.any { sticker in it.stickers })
        val destination = room()
        check(destination.membership() == Membership.JOINED && !destination.isSpace())
        owner.requireCurrent()
        destination.sendRaw("m.sticker", sticker.sendContentJson(threadRoot))
        writes.withLock {
            pendingRecents = StickerShortlist.pushRecent(StickerRef(sticker), pendingRecents)
            try {
                val api = client()
                val event = BipeRegistries.STICKER_RECENTS_EVENT_TYPE
                val recent = (pendingRecents + api.shortlist(event)).distinct().take(BipeRegistries.STICKER_RECENTS_MAX)
                api.setShortlist(event, recent)
                pendingRecents = emptyList()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed shortlist write must not cause a second sticker event. */ }
        }
    }.await()

    suspend fun toggleFavourite(sticker: Sticker) = owner.async {
        writes.withLock {
            val api = client()
            val event = BipeRegistries.STICKER_FAVOURITES_EVENT_TYPE
            api.setShortlist(event, StickerShortlist.toggleFavourite(StickerRef(sticker), api.shortlist(event)))
        }
    }.await()

    suspend fun importStickers(candidates: List<StickerImportCandidate>): Int {
        if (candidates.isEmpty()) return 0
        val task = owner.async { writes.withLock { client().addToUserPack(candidates) } }
        return try { task.await() }
        finally { withContext(NonCancellable) { task.cancelAndJoin() } }
    }

    suspend fun saveReceived(rowId: String) = owner.async {
        writes.withLock {
            val message = row(rowId) ?: error("Message unavailable")
            val media = message.media ?: error("Sticker unavailable")
            check(!message.own && message.eventId != null && media.kind == MessageMedia.Kind.STICKER)
            val source = MediaSource.fromJson(media.sourceJson)
            val url = try { source.url() } finally { source.destroy() }
            check(URI(url).scheme == "mxc")
            val regular = MediaSource.fromUrl(url)
            try { check(regular.toJson() == media.sourceJson) } finally { regular.destroy() }
            val api = client()
            owner.requireCurrent()
            api.saveToUserPack(url, media.width, media.height, media.mimeType, media.size)
        }
    }.await()
}
