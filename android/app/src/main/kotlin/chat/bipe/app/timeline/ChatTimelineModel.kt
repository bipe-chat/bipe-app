// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.InviterGreetings
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.media.MediaMessage
import chat.bipe.app.media.VoiceMessage
import chat.bipe.app.polls.PollData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.messageEventContentNew
import uniffi.matrix_sdk_base.EncryptionState
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** Type TimelineMessage of spec chat-timeline, with the cause of spec message-undecryptable. */
data class TimelineMessage(
    val id: String,
    val kind: Kind,
    val body: String?,
    val senderId: String,
    val senderName: String,
    val isOwn: Boolean,
    val sentAt: Long,
    val sendState: SendState,
    val cause: UndecryptableCause?,
    /** Kind POLL: the data of spec poll-detail. */
    val poll: PollData? = null,
    /** Kind MEDIA: image, video, file or sticker (spec timeline-media). */
    val media: MediaMessage? = null,
    /** Kind VOICE: voice message or audio file (spec voice-message). */
    val voice: VoiceMessage? = null,
) {
    enum class Kind { TEXT, MEDIA, VOICE, UNDECRYPTABLE, POLL, UNSUPPORTED }

    enum class SendState { SENT, SENDING, FAILED }

    /** Bipe's grouping of the SDK UtdCause (spec message-undecryptable). */
    enum class UndecryptableCause { WAITING, BEFORE_JOINED, HISTORY_NOT_ON_THIS_PHONE, NOT_SHARED_WITH_THIS_PHONE, UNAPPROVED_SENDER_DEVICE }
}

/** Type MessageAction of spec message-actions, in the order of the sheet. */
enum class MessageAction { REPLY, EDIT, COPY, RETRY, DELETE, REPORT }

/** Type MessageEdit of spec message-edit. */
data class MessageEdit(val rowId: String, val originalText: String)

/** State of spec message-report. */
data class MessageReport(
    val rowId: String,
    val eventId: String,
    val senderId: String,
    val senderName: String,
    val reason: String = "",
    val alsoBlock: Boolean = false,
    val isSending: Boolean = false,
    val error: Boolean = false,
)

/** One-time notices of the timeline specs. */
enum class TimelineNotice { COPIED, REACTION_FAILED, EDIT_FAILED, DELETE_FAILED, REPORTED }

/** The interaction state of the message specs: one value, so the screen reads it in one place. */
data class TimelineInteractions(
    // message-actions
    val actionsRowId: String? = null,
    // message-reply
    val replyingTo: MessageReply? = null,
    val replyingToRowId: String? = null,
    val scrollTarget: String? = null,
    val highlightedRowId: String? = null,
    // message-reactions
    val pickerOpenFor: String? = null,
    val reactionSheet: MessageReaction? = null,
    // message-edit
    val editing: MessageEdit? = null,
    val isSavingEdit: Boolean = false,
    // message-delete
    val confirmDeleteRowId: String? = null,
    // message-report
    val report: MessageReport? = null,
    // read-receipts
    val readersSheet: List<ReceiptReader>? = null,
    // typing-indicator
    val typingNames: List<String> = emptyList(),
    val notice: TimelineNotice? = null,
)

/**
 * Specs chat-timeline, timeline-rows, message-actions, message-reply, message-reactions, message-edit,
 * message-delete, message-report, read-receipts and typing-indicator (bipe-shared product/screens).
 */
class ChatTimelineModel(
    private val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
    private val greetings: InviterGreetings,
    private val strings: RowStrings,
    /** Spec typing-indicator sharesTyping: the presence setting (bipe_privacy_presence). */
    private val sharesTyping: () -> Boolean,
) {
    private val _roomName = MutableStateFlow("")
    val roomName: StateFlow<String> = _roomName.asStateFlow()
    private val _rows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val rows: StateFlow<List<TimelineRow>> = _rows.asStateFlow()
    /** The messages of the rows, oldest first (polls read their data here). */
    private val _messages = MutableStateFlow<List<TimelineMessage>>(emptyList())
    val messages: StateFlow<List<TimelineMessage>> = _messages.asStateFlow()
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    private val _reachedStart = MutableStateFlow(false)
    val reachedStart: StateFlow<Boolean> = _reachedStart.asStateFlow()
    /** signup-done: the inviter while this chat has no first message (ADR 0034). */
    private val _inviterHint = MutableStateFlow<InviterGreetings.Greeting?>(null)
    val inviterHint: StateFlow<InviterGreetings.Greeting?> = _inviterHint.asStateFlow()
    private val _isDm = MutableStateFlow(false)
    val isDm: StateFlow<Boolean> = _isDm.asStateFlow()
    private val _isPrivateRoom = MutableStateFlow(true)

    /** A DM or a group that needs an invite, for the show-media setting (spec timeline-media). */
    val isPrivateRoom: StateFlow<Boolean> = _isPrivateRoom.asStateFlow()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    private val _ui = MutableStateFlow(TimelineInteractions())
    val ui: StateFlow<TimelineInteractions> = _ui.asStateFlow()

    private var room: Room? = null
    var timeline: Timeline? = null
        private set
    private val items = mutableListOf<TimelineItem>()
    private var handle: TaskHandle? = null
    private var typingHandle: TaskHandle? = null
    private var started = false
    private var facts = RoomFacts(ownUserId = session.userId)
    private val membersLoading = mutableSetOf<String>()
    private var keptDraft = ""
    private var typingSentAt: Long? = null
    private var typingUserIds: List<String> = emptyList()
    private var lastReadEventId: String? = null
    private var noticeJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            this@ChatTimelineModel.room = room
            _roomName.value = room.displayName() ?: roomId
            greetings.get()?.let { greeting ->
                if (runCatching { session.client.getDmRoom(greeting.userId) }.getOrNull()?.id() == roomId) _inviterHint.value = greeting
            }
            val configuration = TimelineConfiguration(
                focus = TimelineFocus.Live(hideThreadedEvents = false),
                filter = TimelineFilter.All,
                internalIdPrefix = null,
                dateDividerMode = DateDividerMode.DAILY,
                trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                reportUtds = false,
            )
            val timeline = runCatching { room.timelineWithConfiguration(configuration) }.getOrNull() ?: return@launch
            this@ChatTimelineModel.timeline = timeline
            loadRoomFacts(room)
            val typing = Channel<List<String>>(Channel.CONFLATED)
            typingHandle = room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                override fun call(typingUserIds: List<String>) {
                    typing.trySend(typingUserIds)
                }
            })
            launch { for (userIds in typing) typingChanged(userIds) }
            val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
            handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diffs.trySend(diff)
                }
            })
            loadHistory()
            for (batch in diffs) {
                batch.forEach { items.applyDiff(it.toVectorDiff()) }
                rebuild()
            }
        }
    }

    /** Called when the screen goes away: typing off (spec typing-indicator draftChanged). */
    fun close() {
        sendTyping(false)
        handle?.cancel()
        typingHandle?.cancel()
    }

    private suspend fun loadRoomFacts(room: Room) {
        runCatching { room.roomInfo() }.getOrNull()?.let { info ->
            facts = facts.copy(isDm = info.isDm, joinedMembers = info.joinedMembersCount.toInt())
        }
        runCatching { room.latestEncryptionState() }.getOrNull()?.let { state ->
            facts = facts.copy(isEncrypted = state != EncryptionState.NOT_ENCRYPTED)
        }
        runCatching { room.getPowerLevels() }.getOrNull()?.let { powerLevels ->
            facts = facts.copy(canRedactOwn = powerLevels.canOwnUserRedactOwn(), canRedactOther = powerLevels.canOwnUserRedactOther())
        }
        _isDm.value = facts.isDm
        _isPrivateRoom.value = runCatching { room.isPublic() }.getOrNull() != true
    }

    fun row(id: String?): TimelineRow? = id?.let { rowId -> _rows.value.firstOrNull { it.id == rowId } }

    val canSend: Boolean
        get() {
            val text = _draft.value.trim()
            if (text.isEmpty()) return false
            val editing = _ui.value.editing ?: return true
            return !_ui.value.isSavingEdit && text != editing.originalText
        }

    // chat-timeline

    /** Action loadHistory: back-paginates 20 events. Does nothing at the start or while a load runs. */
    fun loadHistory() {
        scope.launch { paginate() }
    }

    private suspend fun paginate() {
        val timeline = timeline ?: return
        if (_reachedStart.value || _isLoadingHistory.value) return
        _isLoadingHistory.value = true
        try {
            if (runCatching { timeline.paginateBackwards(HISTORY_PAGE) }.getOrDefault(false)) {
                _reachedStart.value = true
            }
        } finally {
            _isLoadingHistory.value = false
        }
    }

    /** Action editDraft, with typing-indicator draftChanged. */
    fun editDraft(text: String) {
        _draft.value = text
        if (_ui.value.editing != null) return
        if (text.isBlank()) {
            sendTyping(false)
        } else if (typingSentAt.let { it == null || System.currentTimeMillis() - it >= TYPING_INTERVAL_MS }) {
            sendTyping(true)
        }
    }

    /** Action send (chat-timeline), sendReply (message-reply) or saveEdit (message-edit). */
    fun send() {
        if (_ui.value.editing != null) {
            saveEdit()
            return
        }
        val text = _draft.value.trim()
        val timeline = timeline ?: return
        if (text.isEmpty()) return
        _draft.value = ""
        sendTyping(false)
        val replyingTo = _ui.value.replyingTo
        _ui.update { it.copy(replyingTo = null, replyingToRowId = null) }
        scope.launch {
            runCatching {
                val content = messageEventContentNew(MessageType.Text(TextMessageContent(text, null)))
                if (replyingTo != null) timeline.sendReply(content, replyingTo.eventId) else timeline.send(content)
            }
        }
    }

    /** The action of the inviter hint (spec signup-done notes): sends [greeting] (bipe_inviter_greeting). */
    fun sendGreeting(greeting: String) {
        val timeline = timeline ?: return
        scope.launch { runCatching { timeline.send(messageEventContentNew(MessageType.Text(TextMessageContent(greeting, null)))) } }
    }

    /** Action retrySend: sends a failed own message again. */
    fun retrySend(id: String) {
        val sendHandle = item(id)?.asEvent()?.lazyProvider?.getSendHandle() ?: return
        scope.launch { runCatching { sendHandle.tryResend() } }
    }

    /** Action cancelUpload of spec timeline-media: cancels the send of own media and removes the row. */
    fun cancelUpload(id: String) {
        val sendHandle = item(id)?.asEvent()?.lazyProvider?.getSendHandle() ?: return
        scope.launch { runCatching { sendHandle.abort() } }
    }

    // timeline-rows

    /** Action markAsRead: a public read receipt (ADR 0050) for the newest event, while the newest rows are on screen. */
    fun markAsRead() {
        val timeline = timeline ?: return
        scope.launch {
            val newest = timeline.latestEventId() ?: return@launch
            if (newest == lastReadEventId) return@launch
            lastReadEventId = newest
            runCatching { timeline.markAsRead(ReceiptType.READ) }
        }
    }

    // message-actions

    /** Action open: date, notice and deleted rows have no sheet. Returns true when the sheet opens. */
    fun openActions(rowId: String): Boolean {
        val row = row(rowId) ?: return false
        if (row.kind != TimelineRow.Kind.MESSAGE && row.kind != TimelineRow.Kind.UNDECRYPTABLE) return false
        if (!showsReactions(row) && actions(row).isEmpty()) return false
        _ui.update { it.copy(actionsRowId = rowId) }
        return true
    }

    fun closeActions() {
        _ui.update { it.copy(actionsRowId = null) }
    }

    /** The actions that apply to a row (spec message-actions notes). */
    fun actions(row: TimelineRow): List<MessageAction> {
        val message = row.message ?: return emptyList()
        val isSent = message.sendState == TimelineMessage.SendState.SENT && row.details?.eventId != null
        if (row.kind == TimelineRow.Kind.UNDECRYPTABLE) {
            return if (!message.isOwn && isSent) listOf(MessageAction.REPORT) else emptyList()
        }
        val details = row.details ?: return emptyList()
        if (row.kind != TimelineRow.Kind.MESSAGE) return emptyList()
        return MessageAction.entries.filter { action ->
            when (action) {
                MessageAction.REPLY -> details.canReply && isSent
                MessageAction.EDIT -> details.canEdit
                // Media copies its caption, voice its transcript (specs timeline-media, voice-message).
                MessageAction.COPY -> !message.body.isNullOrBlank()
                MessageAction.RETRY -> message.isOwn && message.sendState == TimelineMessage.SendState.FAILED
                MessageAction.DELETE -> details.canDelete || (message.isOwn && !isSent)
                MessageAction.REPORT -> !message.isOwn && isSent
            }
        }
    }

    /** Spec message-actions showsReactions: messages that take reactions (message-reactions notes). */
    fun showsReactions(row: TimelineRow): Boolean =
        row.kind == TimelineRow.Kind.MESSAGE && row.details?.eventId != null && row.message?.sendState == TimelineMessage.SendState.SENT

    /** Action choose. copy puts the text on the clipboard through the screen. */
    fun choose(action: MessageAction, rowId: String, copy: (String) -> Unit) {
        closeActions()
        when (action) {
            MessageAction.REPLY -> startReply(rowId)
            MessageAction.EDIT -> startEdit(rowId)
            MessageAction.COPY -> row(rowId)?.message?.body?.let {
                copy(it)
                show(TimelineNotice.COPIED)
            }
            MessageAction.RETRY -> retrySend(rowId)
            MessageAction.DELETE -> askDelete(rowId)
            MessageAction.REPORT -> openReport(rowId)
        }
    }

    // message-reply

    /** Action startReply: also runs from a swipe on the message. Keeps the draft. */
    fun startReply(rowId: String) {
        val row = row(rowId) ?: return
        val details = row.details ?: return
        val eventId = details.eventId ?: return
        val message = row.message ?: return
        if (!details.canReply) return
        if (_ui.value.editing != null) cancelEdit()
        val reply = MessageReply(eventId, MessageReply.State.READY, if (message.isOwn) strings.you else message.senderName, message.body)
        _ui.update { it.copy(replyingTo = reply, replyingToRowId = rowId) }
    }

    /** Action cancelReply: the draft stays. */
    fun cancelReply() {
        _ui.update { it.copy(replyingTo = null, replyingToRowId = null) }
    }

    /** Action openOriginal: scrolls to the original, back-paginating at most 5 pages to find it. */
    fun openOriginal(eventId: String) {
        scope.launch {
            for (page in 0..ORIGINAL_SEARCH_PAGES) {
                val target = _rows.value.firstOrNull { it.details?.eventId == eventId }
                if (target != null) {
                    _ui.update { it.copy(scrollTarget = target.id, highlightedRowId = target.id) }
                    delay(1500)
                    _ui.update { if (it.highlightedRowId == target.id) it.copy(highlightedRowId = null) else it }
                    return@launch
                }
                if (page == ORIGINAL_SEARCH_PAGES || _reachedStart.value) return@launch
                paginate()
                // The new items reach rows through the timeline listener.
                delay(300)
            }
        }
    }

    fun scrolledToTarget() {
        _ui.update { it.copy(scrollTarget = null) }
    }

    /** Action loadReply: asks the SDK once for the original of a quote that came on screen without details. */
    fun loadReply(rowId: String) {
        val timeline = timeline ?: return
        val details = row(rowId)?.details ?: return
        val reply = details.reply ?: return
        val eventId = details.eventId ?: return
        if (reply.state != MessageReply.State.LOADING || reply.eventId in facts.replyLoadsTried) return
        facts = facts.copy(replyLoadsTried = facts.replyLoadsTried + reply.eventId)
        scope.launch {
            runCatching { timeline.fetchDetailsForEvent(eventId) }
            rebuild()
        }
    }

    // message-reactions

    /** Action toggle: adds or removes this account's reaction with that key. */
    fun toggleReaction(rowId: String, key: String) {
        val timeline = timeline ?: return
        val event = item(rowId)?.asEvent() ?: return
        scope.launch {
            runCatching { timeline.toggleReaction(event.eventOrTransactionId, key) }
                .onFailure { show(TimelineNotice.REACTION_FAILED) }
        }
    }

    /** Action react of message-actions: toggle, then the sheet closes. */
    fun react(rowId: String, key: String) {
        closeActions()
        toggleReaction(rowId, key)
    }

    /** Action moreReactions of message-actions. */
    fun moreReactions(rowId: String) {
        _ui.update { it.copy(actionsRowId = null, pickerOpenFor = rowId) }
    }

    /** A chosen emoji of the picker runs toggle. */
    fun pickEmoji(key: String) {
        val rowId = _ui.value.pickerOpenFor ?: return
        closePicker()
        toggleReaction(rowId, key)
    }

    fun closePicker() {
        _ui.update { it.copy(pickerOpenFor = null) }
    }

    /** Action openSenders: a long press on a pill. */
    fun openSenders(rowId: String, key: String) {
        val reaction = row(rowId)?.details?.reactions?.firstOrNull { it.key == key } ?: return
        _ui.update { it.copy(reactionSheet = reaction) }
    }

    /** Action closeSheet. */
    fun closeSenders() {
        _ui.update { it.copy(reactionSheet = null) }
    }

    // message-edit

    /** Action startEdit: the message text goes into the composer, the unsent draft is kept aside. Ends a reply first. */
    fun startEdit(rowId: String) {
        val row = row(rowId) ?: return
        val body = row.message?.body ?: return
        if (row.details?.canEdit != true) return
        cancelReply()
        if (_ui.value.editing == null) keptDraft = _draft.value
        sendTyping(false)
        _ui.update { it.copy(editing = MessageEdit(rowId, body)) }
        _draft.value = body
    }

    /** Action cancelEdit: puts the kept draft back. */
    fun cancelEdit() {
        _ui.update { it.copy(editing = null) }
        _draft.value = keptDraft
        keptDraft = ""
    }

    /** Action saveEdit. */
    fun saveEdit() {
        val editing = _ui.value.editing ?: return
        val timeline = timeline ?: return
        val event = item(editing.rowId)?.asEvent() ?: return
        if (!canSend) return
        val text = _draft.value.trim()
        _ui.update { it.copy(isSavingEdit = true) }
        scope.launch {
            runCatching {
                val content = messageEventContentNew(MessageType.Text(TextMessageContent(text, null)))
                timeline.edit(event.eventOrTransactionId, EditedContent.RoomMessage(content))
            }.onSuccess {
                _ui.update { it.copy(isSavingEdit = false) }
                cancelEdit()
            }.onFailure {
                _ui.update { it.copy(isSavingEdit = false) }
                show(TimelineNotice.EDIT_FAILED)
            }
        }
    }

    // message-delete

    /** Action askDelete: an own message that is not sent yet is dropped without the question. */
    fun askDelete(rowId: String) {
        val message = row(rowId)?.message ?: return
        if (message.isOwn && message.sendState != TimelineMessage.SendState.SENT) {
            val sendHandle = item(rowId)?.asEvent()?.lazyProvider?.getSendHandle() ?: return
            scope.launch { runCatching { sendHandle.abort() } }
            return
        }
        _ui.update { it.copy(confirmDeleteRowId = rowId) }
    }

    /** Action confirmDelete: deletes for everyone, without a reason. */
    fun confirmDelete() {
        val rowId = _ui.value.confirmDeleteRowId ?: return
        cancelDelete()
        val timeline = timeline ?: return
        val event = item(rowId)?.asEvent() ?: return
        scope.launch {
            runCatching { timeline.redactEvent(event.eventOrTransactionId, null) }
                .onFailure { show(TimelineNotice.DELETE_FAILED) }
        }
    }

    /** Action cancelDelete. */
    fun cancelDelete() {
        _ui.update { it.copy(confirmDeleteRowId = null) }
    }

    // message-report

    private fun openReport(rowId: String) {
        val message = row(rowId)?.message ?: return
        val event = item(rowId)?.asEvent() ?: return
        val eventId = TimelineRowBuilder.eventId(event) ?: return
        _ui.update { it.copy(report = MessageReport(rowId, eventId, message.senderId, message.senderName)) }
    }

    /** Action editReason. */
    fun editReportReason(text: String) {
        _ui.update { state -> state.copy(report = state.report?.copy(reason = text)) }
    }

    /** Action setAlsoBlock. */
    fun setReportAlsoBlock(on: Boolean) {
        _ui.update { state -> state.copy(report = state.report?.copy(alsoBlock = on)) }
    }

    /** Action cancel of message-report. */
    fun cancelReport() {
        _ui.update { it.copy(report = null) }
    }

    /** Action send of message-report: the report, then the block when alsoBlock is on. */
    fun sendReport() {
        val room = room ?: return
        val report = _ui.value.report ?: return
        if (report.isSending) return
        _ui.update { it.copy(report = report.copy(isSending = true, error = false)) }
        val reason = report.reason.trim()
        scope.launch {
            val reported = runCatching { room.reportContent(report.eventId, reason.ifEmpty { null }) }.isSuccess
            if (!reported) {
                _ui.update { it.copy(report = report.copy(isSending = false, error = true)) }
                return@launch
            }
            if (report.alsoBlock) {
                // A failed block still counts the report as sent (spec message-report notes).
                runCatching { session.client.ignoreUser(report.senderId) }
            }
            _ui.update { it.copy(report = null) }
            show(TimelineNotice.REPORTED)
        }
    }

    // read-receipts

    /** Action openReaders: group only. */
    fun openReaders(rowId: String) {
        if (facts.isDm) return
        val readers = row(rowId)?.details?.receipt?.readersUpToHere?.takeIf { it.isNotEmpty() } ?: return
        _ui.update { it.copy(readersSheet = readers) }
    }

    /** Action closeReaders. */
    fun closeReaders() {
        _ui.update { it.copy(readersSheet = null) }
    }

    // typing-indicator

    private fun typingChanged(userIds: List<String>) {
        if (!sharesTyping()) {
            typingUserIds = emptyList()
        } else {
            typingUserIds = userIds.filter { it != session.userId }
            loadMembers(typingUserIds.toSet() - facts.members.keys)
        }
        updateTypingNames()
    }

    private fun updateTypingNames() {
        _ui.update { state -> state.copy(typingNames = typingUserIds.map { facts.members[it]?.name ?: it }) }
    }

    private fun sendTyping(isTyping: Boolean) {
        val room = room ?: return
        if (!sharesTyping()) return
        if (isTyping) {
            typingSentAt = System.currentTimeMillis()
        } else {
            if (typingSentAt == null) return
            typingSentAt = null
        }
        scope.launch { runCatching { room.typingNotice(isTyping) } }
    }

    // Rows

    private fun item(rowId: String): TimelineItem? = items.firstOrNull { it.uniqueId().id == rowId }

    private fun rebuild() {
        val built = TimelineRowBuilder.rows(items, facts, strings)
        _rows.value = built.rows
        _messages.value = built.rows.mapNotNull { it.message }
        if (built.hitStart) _reachedStart.value = true
        _inviterHint.value?.let { hint ->
            val messages = built.rows.mapNotNull { it.message }
            if (messages.any { it.isOwn && it.kind != TimelineMessage.Kind.UNSUPPORTED }) {
                greetings.clear()
                _inviterHint.value = null
            } else if (messages.any { it.senderId == hint.userId && it.kind != TimelineMessage.Kind.UNSUPPORTED }) {
                _inviterHint.value = null
            }
        }
        loadMembers(built.unknownUsers)
    }

    /** Display names and avatars of members for receipts, reactions and typing. */
    private fun loadMembers(userIds: Set<String>) {
        val room = room ?: return
        val missing = userIds - membersLoading
        if (missing.isEmpty()) return
        membersLoading += missing
        scope.launch {
            val loaded = missing.associateWith { userId ->
                val name = runCatching { room.memberDisplayName(userId) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: userId
                MemberInfo(name, runCatching { room.memberAvatarUrl(userId) }.getOrNull())
            }
            facts = facts.copy(members = facts.members + loaded)
            updateTypingNames()
            rebuild()
        }
    }

    private fun show(notice: TimelineNotice) {
        _ui.update { it.copy(notice = notice) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(2000)
            _ui.update { it.copy(notice = null) }
        }
    }

    private companion object {
        const val HISTORY_PAGE: UShort = 20u
        /** Spec message-reply openOriginal: at most 5 pages back. */
        const val ORIGINAL_SEARCH_PAGES = 5
        /** Spec typing-indicator: typing on at most once every 4 seconds. */
        const val TYPING_INTERVAL_MS = 4000L
    }
}
