// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.app.notifications.NewsRoomMark
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.timeline.AssistantDraftsStore
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.previewText
import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.TimelineItemContent
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.composer.ComposerMarkdown
import chat.bipe.app.composer.keepDraft
import chat.bipe.app.composer.newMessageDraft
import chat.bipe.shared.BipeOfficialAccounts
import chat.bipe.shared.BipeRowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.TaskHandle
import chat.bipe.app.voce.NotesRoom
import chat.bipe.shared.BipeChatRowName
import chat.bipe.shared.BipeFavouritesOrder
import chat.bipe.shared.generated.BipeHomeScore
import java.util.UUID

/** Type ChatSummary of spec chat-list. */
data class ChatSummary(
    val roomId: String,
    val name: String,
    val avatarUrl: String?,
    val lastMessage: String?,
    val lastActivity: Long?,
    val unreadCount: Int,
    val isDirect: Boolean,
    /** Joined and invited members. Not in the spec type: it decides the scope. */
    val memberCount: Int,
    /** Unread mentions and replies, for the Grupos badge (spec app-tabs gruposAttention). */
    val mentionCount: Int,
    /** The other member of a DM, for the avatar account-type lookup. */
    val peerUserId: String? = null,
    /** Spec chat-list notes, Group avatar: members shown when a group has no photo. */
    val avatarMembers: List<chat.bipe.app.people.Person> = emptyList(),
    /** RoomInfo.isDm, the one DM test (ADR 0141). */
    val isDm: Boolean = false,
    val isMuted: Boolean = false,
    val isMarkedUnread: Boolean = false,
    val isLowPriority: Boolean = false,
    /** RoomInfo.isFavourite (m.favourite): the chat shows in the favourites row (spec chat-list). */
    val isFavourite: Boolean = false,
    val latestIncomingMs: Long? = null,
    /** The account's news room (NewsRoomMark, bipe-chat/bipe#978). */
    val isNewsRoom: Boolean = false,
    /** Spec chat-list, Mural tiles: the previews of the newest local messages, oldest first. */
    val recentMessages: List<String> = emptyList(),
    /** Spec chat-list, Mural inputs: the newest local message has another sender. */
    val lastFromOther: Boolean = false,
    /** Spec chat-list, messagesPerWeek: local message-like events of the window. Never asks the server. */
    val messagesPerWeek: Int = 0,
) {
    val isUnread: Boolean get() = unreadCount > 0 || isMarkedUnread

    /** ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test. */
    val scope: ChatScope
        get() = if (isDm) ChatScope.CONVERSAS else ChatScope.GRUPOS

    /** Spec chat-list isOfficial: a DM with an official Bipe account (ADR 0117), by exact user ID only, or the news room. */
    val isOfficial: Boolean
        get() = isNewsRoom || (isDm && BipeOfficialAccounts.isOfficial(peerUserId))
}

/** State scope of spec chat-list. TODAS: the Conversas tab of a company account, DMs and groups together (spec app-tabs). */
enum class ChatScope {
    CONVERSAS, GRUPOS, TODAS;

    /** True when a chat of [chat] scope shows in this one. */
    fun shows(chat: ChatScope): Boolean = this == TODAS || this == chat
}

/** Spec chat-list (bipe-shared product/screens/chat-list.json). */
class ChatListModel(
    val session: AccountSession,
    private val scope: CoroutineScope,
    private val undecryptableText: String,
    private val unnamedChatText: String,
    /** Resolves the media kind labels of the previews. */
    private val label: (Int) -> String,
    val notes: NotesRoom,
    val archive: ChatArchiveModel,
) {
    enum class Phase { LOADING, EMPTY, LOADED }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val rooms = mutableListOf<Room>()
    private val archivedRooms = mutableListOf<Room>()
    private val favouriteRooms = mutableListOf<Room>()
    private var archiveEntries: RoomListEntriesWithDynamicAdaptersResult? = null
    private var favouriteEntries: RoomListEntriesWithDynamicAdaptersResult? = null
    private var archiveJob: Job? = null
    private var favouriteJob: Job? = null
    private val subscriptionOwner = UUID.randomUUID().toString()
    /** One model belongs to one account session; absent previews must not erase a known time or text. */
    private val knownRows = mutableMapOf<String, BipeRowActivity.Row>()
    /** Rows take a newer message of the local timeline as their date and text (bipe-chat/bipe#592). */
    private val rowActivity = RowActivityScanner(session.client, scope, ::rowPreview) { refreshSummaries() }.also { scanner ->
        // Spec read-receipts, ack send: the newest event from the other person of a DM, once per room list change.
        scanner.onDmIncoming = { room, eventId, sender -> session.delivered.send(room, eventId, sender) }
    }
    private val extraRoomIds = mutableSetOf<String>()
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult? = null
    private val handles = mutableListOf<TaskHandle>()
    private var summaryJob: Job? = null
    private val _markingRoomId = MutableStateFlow<String?>(null)
    val markingRoomId = _markingRoomId.asStateFlow()
    private val _readActionFailed = MutableStateFlow(false)
    val readActionFailed = _readActionFailed.asStateFlow()
    private val _favouriteFailed = MutableStateFlow(false)
    val favouriteFailed = _favouriteFailed.asStateFlow()
    /** The favourites order of spec chat-list (registries/chat-favourites.json): room IDs, first first. */
    private val _favouritesOrder = MutableStateFlow<List<String>>(emptyList())
    val favouritesOrder = _favouritesOrder.asStateFlow()
    /** A local order that is not on the server yet: a read must not replace it. */
    private var favouritesOrderUnsaved = false
    /** A local read found an undecryptable message: feeds encryption-status noteUndecryptable (bipe-chat/bipe#853). */
    private val _hasUndecryptable = MutableStateFlow(false)
    val hasUndecryptable = _hasUndecryptable.asStateFlow()
    private var failedReadIntent: Pair<String, Boolean>? = null
    private var started = false
    private val clearChanges = scope.launch {
        ClearedChatsStore.revision.collect { refreshSummaries() }
    }
    /** Spec drafts notes: the drafts room never shows in the list, its badges or the share targets. */
    private val draftsChanges = scope.launch {
        AssistantDraftsStore.revision.collect { refreshSummaries() }
    }

    /** Subscribes to the room list service: joined chats, newest activity first. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            refreshNotes()
            AssistantDraftsStore.learnRoomId(null, session)
            session.delivered.start()
            val roomList = runCatching { session.syncService.roomListService().allRooms() }.getOrNull() ?: return@launch
            val loading = Channel<RoomListLoadingState>(Channel.UNLIMITED)
            runCatching {
                roomList.loadingState(object : RoomListLoadingStateListener {
                    override fun onUpdate(state: RoomListLoadingState) {
                        loading.trySend(state)
                    }
                })
            }.getOrNull()?.let { result ->
                handles += result.stateStream
                onLoadingState(result.state)
            }
            val updates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
            val result = roomList.entriesWithDynamicAdapters(
                PAGE_SIZE,
                object : RoomListEntriesListener {
                    override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                        updates.trySend(roomEntriesUpdate)
                    }
                },
            )
            result.controller().setFilter(filter())
            handles += result.entriesStream()
            entries = result
            // Dedicated tag-filtered streams find archives and favourites outside the active first page.
            tagStream(roomList, RoomListEntriesDynamicFilterKind.LowPriority, archivedRooms).let { (entries, job) ->
                archiveEntries = entries
                archiveJob = job
            }
            loadFavouritesOrder()
            tagStream(roomList, RoomListEntriesDynamicFilterKind.Favourite, favouriteRooms, ::loadFavouritesOrder).let { (entries, job) ->
                favouriteEntries = entries
                favouriteJob = job
            }
            launch {
                for (state in loading) onLoadingState(state)
            }
            for (batch in updates) {
                batch.forEach { update ->
                    // A room that moved or changed may have newer events than the ones read for its date.
                    when (update) {
                        is RoomListEntriesUpdate.Set -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.Insert -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushFront -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushBack -> rowActivity.invalidate(update.value.id())
                        else -> Unit
                    }
                    rooms.applyDiff(update.toVectorDiff())
                }
                refreshSummaries()
                // Publish cached rows first; subscription enables SDK latest-event calculation and online catchup.
                updateSubscriptions()
                // A first DM can come after start: the ack receiver binds to it (no work while one runs).
                session.delivered.start()
            }
        }
    }

    /** A room list stream of joined rooms with one tag (m.lowpriority or m.favourite), applied to [target]. */
    private fun CoroutineScope.tagStream(
        roomList: org.matrix.rustcomponents.sdk.RoomList, tag: RoomListEntriesDynamicFilterKind, target: MutableList<Room>,
        onUpdate: (suspend () -> Unit)? = null,
    ): Pair<RoomListEntriesWithDynamicAdaptersResult, Job> {
        val updates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
        val entries = roomList.entriesWithDynamicAdapters(PAGE_SIZE, object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) { updates.trySend(roomEntriesUpdate) }
        })
        entries.controller().setFilter(RoomListEntriesDynamicFilterKind.All(listOf(
            RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace,
            tag, RoomListEntriesDynamicFilterKind.DeduplicateVersions,
        )))
        handles += entries.entriesStream()
        val job = launch {
            for (batch in updates) {
                batch.forEach { update ->
                    when (update) {
                        is RoomListEntriesUpdate.Set -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.Insert -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushFront -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushBack -> rowActivity.invalidate(update.value.id())
                        else -> Unit
                    }
                    target.applyDiff(update.toVectorDiff())
                }
                onUpdate?.invoke()
                refreshSummaries()
                updateSubscriptions()
            }
        }
        return entries to job
    }

    private suspend fun updateSubscriptions() {
        session.roomSubscriptions.update(subscriptionOwner,
            (rooms + archivedRooms + favouriteRooms).map { it.id() } + archive.markers.value.keys)
    }

    /** Badges and both tabs use this one unfiltered joined stream. */
    private fun filter(): RoomListEntriesDynamicFilterKind = RoomListEntriesDynamicFilterKind.All(listOf(
        RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace,
        RoomListEntriesDynamicFilterKind.DeduplicateVersions,
    ))

    /** The phase of one scope: empty when the list loaded and the scope has no chat. */
    fun phase(scope: ChatScope, phase: Phase, chats: List<ChatSummary>): Phase = when {
        phase == Phase.LOADING -> Phase.LOADING
        chats.any { scope.shows(it.scope) } -> Phase.LOADED
        listLoaded -> Phase.EMPTY
        else -> Phase.LOADING
    }

    /** Action loadMore: the next page, when the list scrolls near its end. */
    @Suppress("UNUSED_PARAMETER")
    fun loadMore(tab: ChatScope) {
        entries?.controller()?.addOnePage()
        archiveEntries?.controller()?.addOnePage()
        favouriteEntries?.controller()?.addOnePage()
    }

    /** Resolve actions in the immutable owner's room list. */
    fun currentChat(roomId: String): ChatSummary? = _chats.value.firstOrNull { it.roomId == roomId }

    /** Explicit public receipt first, then the manual unread flag. No optimistic fake count or startup gate. */
    fun setUnread(roomId: String, unread: Boolean) {
        if (_markingRoomId.value != null) return
        _markingRoomId.value = roomId
        _readActionFailed.value = false
        scope.launch {
            try {
                check(currentChat(roomId) != null || failedReadIntent == (roomId to unread))
                val room = session.client.getRoom(roomId) ?: error("room unavailable")
                val info = room.roomInfo()
                check(info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED && !info.isSpace)
                if (!unread) room.markAsRead(chat.bipe.app.timeline.ReadReceiptSetting.receiptType(info.isDm))
                room.setUnreadFlag(unread)
                failedReadIntent = null
                refreshSummaries()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedReadIntent = roomId to unread
                _readActionFailed.value = true
            } finally { _markingRoomId.value = null }
        }
    }

    /** Action setFavourite of spec chat-list: the m.favourite tag, so it syncs to the account's other phones. */
    fun setFavourite(roomId: String, favourite: Boolean) {
        _favouriteFailed.value = false
        scope.launch {
            try {
                check(currentChat(roomId) != null)
                val room = session.client.getRoom(roomId) ?: error("room unavailable")
                val info = room.roomInfo()
                check(info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED && !info.isSpace)
                room.setIsFavourite(favourite, null)
                // Spec chat-list favourites order: a new favourite goes last, a removed one leaves the order.
                val order = _favouritesOrder.value
                saveFavouritesOrder(if (favourite) BipeFavouritesOrder.adding(roomId, order) else BipeFavouritesOrder.removing(roomId, order))
                refreshSummaries()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _favouriteFailed.value = true
            }
        }
    }

    /** Action moveFavourite of spec chat-list: moves a favourite among the [shown] favourites of its scope and writes the order. */
    fun moveFavourite(roomId: String, index: Int, shown: List<String>) {
        scope.launch { saveFavouritesOrder(BipeFavouritesOrder.moving(roomId, index, shown, _favouritesOrder.value)) }
    }

    /** Spec chat-list, Mural tiles: the saved composer draft of the room. */
    suspend fun tileDraft(roomId: String): String? = runCatching { session.client.getRoom(roomId) }.getOrNull()?.newMessageDraft()

    /** Spec composer leave, for the reply field of the first tile: keeps the text as the draft of the room, or clears it. */
    fun keepTileDraft(roomId: String, text: String) {
        scope.launch { runCatching { session.client.getRoom(roomId) }.getOrNull()?.keepDraft(text) }
    }

    /**
     * Action replyFromTile of spec chat-list: the send rules of spec composer (Markdown, Timeline.send), then the draft
     * goes and the chat is read. [onResult] gets false when the send failed. The SDK send queue keeps the message after the timeline goes.
     */
    fun replyFromTile(roomId: String, text: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull()
            val sent = runCatching {
                room?.timeline()?.send(ComposerMarkdown.content(text.trim(), emptyList())) ?: error("room unavailable")
            }.isSuccess
            if (sent) {
                runCatching { room?.clearComposerDraft(null) }
                setUnread(roomId, false)
            }
            onResult(sent)
        }
    }

    /** Reads the favourites order from the account data, unless this phone has an order it could not write yet. */
    private suspend fun loadFavouritesOrder() {
        if (favouritesOrderUnsaved) return
        val json = runCatching { session.client.accountData(BipeFavouritesOrder.EVENT_TYPE) }.getOrNull() ?: return
        _favouritesOrder.value = BipeFavouritesOrder.parse(json)
    }

    /** Shows the order at once and writes it. A failed write keeps it on this phone; the next change writes it again. */
    private suspend fun saveFavouritesOrder(order: List<String>) {
        _favouritesOrder.value = order
        favouritesOrderUnsaved = true
        val saved = runCatching { session.client.setAccountData(BipeFavouritesOrder.EVENT_TYPE, BipeFavouritesOrder.content(order)) }
            .isSuccess
        if (saved && _favouritesOrder.value == order) favouritesOrderUnsaved = false
    }

    fun retryReadAction() { failedReadIntent?.let { (roomId, unread) -> setUnread(roomId, unread) } }

    suspend fun refreshNotes() {
        runCatching { notes.refresh() }
        refreshSummaries()
    }

    suspend fun openNotes(title: String): String = try { notes.open(title) } finally { refreshSummaries() }

    fun close() {
        clearChanges.cancel()
        draftsChanges.cancel()
        rowActivity.close()
        handles.forEach { it.cancel() }
        summaryJob?.cancel()
        archiveJob?.cancel()
        favouriteJob?.cancel()
        archive.close()
        session.releaseRoomSubscriptions(subscriptionOwner)
    }

    private fun onLoadingState(state: RoomListLoadingState) {
        if (state is RoomListLoadingState.Loaded) listLoaded = true
        updatePhase()
    }

    private fun updatePhase() {
        _phase.value = when {
            _chats.value.isNotEmpty() -> Phase.LOADED
            listLoaded -> Phase.EMPTY
            else -> Phase.LOADING
        }
    }

    private fun refreshSummaries() {
        if (rowActivity.sawUndecryptable) _hasUndecryptable.value = true
        extraRoomIds += archive.markers.value.keys
        val snapshot = (rooms + archivedRooms + favouriteRooms).distinctBy { it.id() }.toMutableList()
        val loadedIds = snapshot.map { it.id() }.toSet()
        for (id in (extraRoomIds - loadedIds).sorted()) runCatching { session.client.getRoom(id) }.getOrNull()?.let { snapshot += it }
        summaryJob?.cancel()
        summaryJob = scope.launch {
            val draftsRoomId = AssistantDraftsStore.roomId(session.userId)
            val summaries = snapshot.filter { it.id() != notes.roomId && it.id() != draftsRoomId }.mapNotNull { summary(it) }
            val visible = retainingKnownActivity(NativeDmSelection.visible(summaries, session))
            archive.reconcile(visible)
            // SDK order includes server recency stamps and local echoes, even when previews have no timestamp.
            _chats.value = visible
            updatePhase()
        }
    }

    /**
     * The row date and text (BipeRowActivity): a message of the local timeline newer than the SDK latest event, else the
     * SDK latest event, else the newest local event, which includes undecryptable events (bipe-chat/bipe#592), else a time
     * known earlier in this account session. The SDK keeps an older latest event when a message arrived before its room
     * key; the timeline decrypts it later.
     */
    private fun retainingKnownActivity(summaries: List<ChatSummary>): List<ChatSummary> {
        val windowStart = System.currentTimeMillis() - BipeHomeScore.MESSAGES_PER_WEEK_WINDOW_DAYS * MILLIS_PER_DAY
        return summaries.map { summary ->
            val sdk = summary.lastActivity?.let {
                BipeRowActivity.SdkLatest(it, summary.lastMessage, isIncoming = summary.latestIncomingMs != null)
            }
            val local = rowActivity.localEvents(summary.roomId).orEmpty()
            val row = BipeRowActivity.resolve(sdk, local, knownRows[summary.roomId])
            if (row.timestampMs != null) knownRows[summary.roomId] = row
            val messages = rowActivity.localMessages(summary.roomId)
            summary.copy(
                lastActivity = row.timestampMs, lastMessage = row.previewText, latestIncomingMs = row.latestIncomingMs,
                recentMessages = messages?.recent.orEmpty(),
                lastFromOther = messages?.lastFromOther == true,
                messagesPerWeek = messages?.times?.count { it >= windowStart } ?: 0,
            )
        }
    }

    /**
     * The row text of an event (spec chat-list, row text): "missed call" for an incoming call this account missed, a line
     * for a join, leave or room creation, else the preview, with "You: " before an own one and the sender's name before another member's in a group.
     */
    private fun rowPreview(content: TimelineItemContent, isOwn: Boolean, senderName: String, roomId: String, isDm: Boolean): String? {
        if (content is TimelineItemContent.RtcNotification && !isOwn && chat.bipe.app.calls.MissedCalls.isMissed(session.userId, roomId)) {
            return label(R.string.bipe_call_missed)
        }
        membershipLine(content, senderName, isOwn, isDm)?.let { return it }
        val preview = content.previewText(undecryptableText, label) ?: return null
        if (isOwn) return label(R.string.bipe_chat_list_own_preview).format(preview)
        return if (isDm) preview else "$senderName: $preview"
    }

    /** A join (an accepted invite or knock included), a leave or the room creation. null for other events. */
    private fun membershipLine(content: TimelineItemContent, senderName: String, isOwn: Boolean, isDm: Boolean): String? {
        fun line(dm: Int, group: Int, name: String?): String =
            if (name == null) label(if (isDm) dm else group) else label(if (isDm) dm else group).format(name)
        return when (content) {
            is TimelineItemContent.RoomMembership -> {
                val own = content.userId == session.userId
                val name = chat.bipe.app.people.Person.name(content.userId, content.userDisplayName)
                when (content.change) {
                    MembershipChange.JOINED, MembershipChange.INVITATION_ACCEPTED, MembershipChange.KNOCK_ACCEPTED ->
                        if (own) line(R.string.bipe_dm_joined_by_you, R.string.bipe_group_state_event_room_join_by_you, null)
                        else line(R.string.bipe_dm_joined, R.string.bipe_group_state_event_room_join, name)
                    MembershipChange.LEFT ->
                        if (own) line(R.string.bipe_dm_left_by_you, R.string.bipe_group_state_event_room_leave_by_you, null)
                        else line(R.string.bipe_dm_left, R.string.bipe_group_state_event_room_leave, name)
                    else -> null
                }
            }
            is TimelineItemContent.State -> if (content.content is OtherState.RoomCreate) {
                if (isOwn) line(R.string.bipe_dm_created_by_you, R.string.bipe_group_state_event_room_created_by_you, null)
                else line(R.string.bipe_dm_created, R.string.bipe_group_state_event_room_created, senderName)
            } else {
                null
            }
            else -> null
        }
    }

    suspend fun summary(room: Room): ChatSummary? {
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return null
        if (info.membership != org.matrix.rustcomponents.sdk.Membership.JOINED || info.isSpace) return null
        var lastMessage: String? = null
        var lastActivity: Long? = null
        var latestIncomingMs: Long? = null
        when (val latest = room.latestEvent()) {
            is LatestEventValue.Remote -> {
                lastMessage = rowPreview(latest.content, latest.isOwn, latest.profile.displayName(latest.sender), info.id, info.isDm)
                lastActivity = latest.timestamp.toLong()
                if (!latest.isOwn && lastMessage != null) latestIncomingMs = lastActivity
            }
            is LatestEventValue.Local -> {
                lastMessage = rowPreview(latest.content, true, latest.profile.displayName(latest.sender), info.id, info.isDm)
                lastActivity = latest.timestamp.toLong()
            }
            is LatestEventValue.RemoteInvite -> lastActivity = latest.timestamp.toLong()
            // A sticker pack room has no messages (spec sticker-picker notes, Pack room): the chat list hides it.
            LatestEventValue.None -> if (info.isPublic == true && info.historyVisibility is RoomHistoryVisibility.WorldReadable) return null
        }
        val peer = if (info.isDm) info.heroes.firstOrNull { it.userId != session.userId } else null
        // Spec chat-list news room mark: the app-language name, not the room's Portuguese m.room.name.
        val isNewsRoom = NewsRoomMark.roomId(session.userId) == info.id
        val name = if (isNewsRoom) label(chat.bipe.shared.R.string.bipe_news_room_name)
            else BipeChatRowName.resolve(info.id, info.displayName, peer?.displayName, peer?.userId, unnamedChatText,
                dmRoomName = if (info.isDm) info.rawName else null,
                groupHeroes = chat.bipe.app.timeline.groupHeroes(info, session.userId), noName = chat.bipe.app.people.Person.noName())
        return ChatSummary(
            roomId = info.id,
            name = name,
            // ADR 0141: a DM shows the person's photo, never m.room.avatar.
            avatarUrl = if (info.isDm) peer?.avatarUrl else info.avatarUrl,
            lastMessage = lastMessage,
            lastActivity = lastActivity,
            unreadCount = info.numUnreadMessages.toInt(),
            isDirect = info.isDirect,
            memberCount = info.activeMembersCount.toInt(),
            mentionCount = info.numUnreadMentions.toInt(),
            peerUserId = peer?.userId,
            avatarMembers = chat.bipe.app.common.groupAvatarMembers(info, session.userId),
            isDm = info.isDm,
            isMuted = info.cachedUserDefinedNotificationMode == RoomNotificationMode.MUTE,
            isMarkedUnread = info.isMarkedUnread,
            isLowPriority = info.isLowPriority,
            isFavourite = info.isFavourite,
            latestIncomingMs = latestIncomingMs,
            isNewsRoom = isNewsRoom,
        )
    }

    private companion object {
        const val PAGE_SIZE = 50u
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
