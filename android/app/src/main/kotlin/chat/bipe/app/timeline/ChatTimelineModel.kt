// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.Context
import android.os.Build
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.InviterGreetings
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.composer.ComposerMarkdown
import chat.bipe.app.composer.ComposerMention
import chat.bipe.app.composer.MentionDirectory
import chat.bipe.app.composer.MentionSuggestion
import chat.bipe.app.composer.keepDraft
import chat.bipe.app.composer.newMessageDraft
import chat.bipe.app.media.voice.VoiceMediaExpiry
import chat.bipe.app.media.voice.VoiceMediaStore
import chat.bipe.app.media.voice.VoiceRecordingModel
import chat.bipe.shared.api.BipeKeptMedia
import chat.bipe.app.location.LiveLocationMessage
import chat.bipe.app.location.LocationMessage
import chat.bipe.app.media.MediaMessage
import chat.bipe.app.media.VoiceMessage
import chat.bipe.app.polls.PollData
import chat.bipe.shared.R
import chat.bipe.app.groups.GroupPeople
import chat.bipe.shared.BipeHandleChangeStore
import chat.bipe.shared.BipeMoneyPause
import chat.bipe.shared.BipeTheftStatement
import chat.bipe.shared.ScamWarningKind
import org.matrix.rustcomponents.sdk.MembershipState
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.ClientException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import uniffi.ruma_events.MessageLikeEventType

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
    /** Kind LOCATION: m.location (spec location-view). */
    val location: LocationMessage? = null,
    /** Kind LIVE_LOCATION: an MSC3489 live location share (spec location-view, live row). */
    val live: LiveLocationMessage? = null,
    /** m.emote has a display prefix; body stays unchanged for edits and copying. */
    val isEmote: Boolean = false,
    /** Sanitized native text runs; body remains the original text for editing and copying. */
    val richText: chat.bipe.shared.BipeMessageText? = null,
) {
    enum class Kind { TEXT, MEDIA, VOICE, UNDECRYPTABLE, POLL, LOCATION, LIVE_LOCATION, UNSUPPORTED }

    enum class SendState { SENT, SENDING, FAILED }

    /** Bipe's grouping of the SDK UtdCause (spec message-undecryptable). */
    enum class UndecryptableCause { WAITING, BEFORE_JOINED, HISTORY_NOT_ON_THIS_PHONE, NOT_SHARED_WITH_THIS_PHONE, UNAPPROVED_SENDER_DEVICE }
}

/** Type MessageAction of spec message-actions, in the order of the sheet. */
enum class MessageAction { REPLY, EDIT, COPY, KEEP_AUDIO, UNKEEP_AUDIO, RETRY, DELETE, REPORT }

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
enum class TimelineNotice { COPIED, REACTION_FAILED, EDIT_FAILED, DELETE_FAILED, REPORTED, INVITE_AGAIN_FAILED, KEEP_LIMIT, KEEP_FAILED }

/** Spec chat-timeline peerAway (ADR 0141): the other person of a DM left, or the account is gone. */
enum class PeerAway { NONE, LEFT, ACCOUNT_GONE }

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

/** State of spec composer that the timeline does not hold already (text is the draft). */
data class ComposerState(
    /** The pills in the draft, in order. */
    val mentions: List<ComposerMention> = emptyList(),
    /** @ suggestions in a group; empty hides the list. */
    val suggestions: List<MentionSuggestion> = emptyList(),
    /** false: the composer is off, with the reason bar instead. */
    val canPost: Boolean = true,
    /** The poll row of attachment-picker. */
    val canPoll: Boolean = true,
    /** mode recording: voice-recording replaces the field. */
    val recording: VoiceRecordingModel? = null,
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
    private val unnamedChatText: String,
    /** Spec typing-indicator sharesTyping: the presence setting (bipe_privacy_presence). */
    private val sharesTyping: () -> Boolean,
    /** Spec handle-change-line: the account's handle-change cache. */
    private val handleChanges: BipeHandleChangeStore? = null,
    /** Expiry and keep state of this account's voice messages (ADR 0164). */
    val voiceMedia: VoiceMediaStore? = null,
) {
    private val _roomName = MutableStateFlow("")
    val roomName: StateFlow<String> = _roomName.asStateFlow()
    private val _roomAvatarUrl = MutableStateFlow<String?>(null)
    val roomAvatarUrl = _roomAvatarUrl.asStateFlow()
    private val _peerUserId = MutableStateFlow<String?>(null)
    val peerUserId = _peerUserId.asStateFlow()
    private val _peerAway = MutableStateFlow(PeerAway.NONE)
    val peerAway: StateFlow<PeerAway> = _peerAway.asStateFlow()
    private val _invitingAgain = MutableStateFlow(false)
    /** Spec chat-timeline theftMark: a valid theft statement for the other person of a DM, else null. */
    private val _theftMark = MutableStateFlow<BipeTheftStatement?>(null)
    val theftMark: StateFlow<BipeTheftStatement?> = _theftMark.asStateFlow()
    val invitingAgain: StateFlow<Boolean> = _invitingAgain.asStateFlow()
    /** The person whose profile was checked for ACCOUNT_GONE, so the lookup runs once per leave. */
    private var accountGoneChecked: Pair<String, Boolean>? = null
    private val _isGroup = MutableStateFlow(false)
    val isGroup = _isGroup.asStateFlow()
    private val _composer = MutableStateFlow(ComposerState())
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()
    private val mentionDirectory = MentionDirectory()
    private var draftSave: Job? = null
    private val _rows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val rows: StateFlow<List<TimelineRow>> = _rows.asStateFlow()
    /** The messages of the rows, oldest first (polls read their data here). */
    private val _messages = MutableStateFlow<List<TimelineMessage>>(emptyList())
    val messages: StateFlow<List<TimelineMessage>> = _messages.asStateFlow()
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    private val _reachedStart = MutableStateFlow(false)
    val reachedStart: StateFlow<Boolean> = _reachedStart.asStateFlow()
    /** Goes up after each load that did not fail, so the screen asks for the next page while the top is on screen. */
    private val _historyLoads = MutableStateFlow(0)
    val historyLoads: StateFlow<Int> = _historyLoads.asStateFlow()
    /** signup-done: the inviter while this chat has no first message (ADR 0034). */
    private val _inviterHint = MutableStateFlow<InviterGreetings.Greeting?>(null)
    val inviterHint: StateFlow<InviterGreetings.Greeting?> = _inviterHint.asStateFlow()
    private val _isDm = MutableStateFlow(false)
    val isDm: StateFlow<Boolean> = _isDm.asStateFlow()
    /** Spec call: the room is encrypted, and a call runs in it (RoomInfo.hasRoomCall). */
    private val _isEncrypted = MutableStateFlow(true)
    val isEncrypted: StateFlow<Boolean> = _isEncrypted.asStateFlow()
    private val _hasCall = MutableStateFlow(false)
    val hasCall: StateFlow<Boolean> = _hasCall.asStateFlow()
    private val _isPrivateRoom = MutableStateFlow(true)

    /** A DM or a group that needs an invite, for the show-media setting (spec timeline-media). */
    val isPrivateRoom: StateFlow<Boolean> = _isPrivateRoom.asStateFlow()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    private val _ui = MutableStateFlow(TimelineInteractions())
    val ui: StateFlow<TimelineInteractions> = _ui.asStateFlow()

    private var room: Room? = null

    /** The room, for spec call. */
    val matrixRoom: Room? get() = room
    var timeline: Timeline? = null
        private set
    private val items = mutableListOf<TimelineItem>()
    private var handle: TaskHandle? = null
    private var typingHandle: TaskHandle? = null
    private var started = false
    private var acceptsMenuActions = true
    private var facts = RoomFacts(ownUserId = session.userId)
    private val membersLoading = mutableSetOf<String>()
    private var keptDraft = ""
    private var typingSentAt: Long? = null
    private var typingUserIds: List<String> = emptyList()
    private var lastReadEventId: String? = null
    private var noticeJob: Job? = null

    fun start() {
        acceptsMenuActions = true
        if (started) return
        started = true
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            this@ChatTimelineModel.room = room
            _roomName.value = unnamedChatText
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
            // Action appear of spec composer: a saved newMessage draft fills the field.
            if (_draft.value.isEmpty()) room.newMessageDraft()?.let { _draft.value = it }
            if (!facts.isDm) launch { mentionDirectory.load(room, session.userId) }
            loadHandleChanges(room)
            // The partner's theft mark when the chat opens, then every checkMinutes while it stays open.
            launch {
                while (true) {
                    checkTheftMark()
                    delay(TheftMarkChecks.INTERVAL_MILLIS)
                }
            }
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
                loadRoomFacts(room)
                rebuild()
            }
        }
    }

    /** Called when the screen goes away: typing off (spec typing-indicator draftChanged). */
    fun close() {
        acceptsMenuActions = false
        _ui.update { it.copy(actionsRowId = null, pickerOpenFor = null) }
        sendTyping(false)
        leave()
        handle?.cancel()
        typingHandle?.cancel()
    }

    private suspend fun loadRoomFacts(room: Room) {
        runCatching { room.roomInfo() }.getOrNull()?.let { info ->
            facts = facts.copy(isDm = info.isDm, joinedMembers = info.joinedMembersCount.toInt())
            _isGroup.value = !info.isDm && !info.isSpace
            val peer = if (info.isDm) info.heroes.firstOrNull { it.userId != session.userId } else null
            val peerName = peer?.displayName?.takeIf { it.isNotBlank() }
                ?: peer?.userId?.let { chat.bipe.app.people.Person.localpart(it) } ?: unnamedChatText
            _roomName.value = if (info.isDm) peerName else info.displayName ?: info.id
            _roomAvatarUrl.value = if (info.isDm) peer?.avatarUrl else info.avatarUrl
            _peerUserId.value = peer?.userId
            _hasCall.value = info.hasRoomCall
            loadPeerAway(room, peer?.userId)
        }
        runCatching { room.latestEncryptionState() }.getOrNull()?.let { state ->
            facts = facts.copy(isEncrypted = state != EncryptionState.NOT_ENCRYPTED)
        }
        runCatching { room.getPowerLevels() }.getOrNull()?.let { powerLevels ->
            facts = facts.copy(canRedactOwn = powerLevels.canOwnUserRedactOwn(), canRedactOther = powerLevels.canOwnUserRedactOther())
            _composer.update {
                it.copy(
                    canPost = powerLevels.canOwnUserSendMessage(MessageLikeEventType.RoomMessage),
                    canPoll = powerLevels.canOwnUserSendMessage(MessageLikeEventType.PollStart),
                )
            }
        }
        _isDm.value = facts.isDm
        _isEncrypted.value = facts.isEncrypted
        _isPrivateRoom.value = runCatching { room.isPublic() }.getOrNull() != true
    }

    private suspend fun checkTheftMark() {
        val peer = _peerUserId.value
        if (!facts.isDm || peer == null) {
            _theftMark.value = null
            return
        }
        val statement = TheftMarkChecks.statement(peer, session)
        // The chat may have changed partner while the call ran.
        if (_peerUserId.value == peer) _theftMark.value = statement
    }

    /** bipe_theft_message_marker: a message of the marked partner sent from stolen_at minus markedMessageMinutes on. */
    fun showsTheftMarker(message: TimelineMessage, mark: BipeTheftStatement?): Boolean =
        mark != null && !message.isOwn && message.senderId == mark.sub && message.sentAt >= mark.markerStartMillis

    fun row(id: String?): TimelineRow? = id?.let { rowId -> _rows.value.firstOrNull { it.id == rowId } }

    val canSend: Boolean
        get() {
            val text = _draft.value.trim()
            if (text.isEmpty()) return false
            val editing = _ui.value.editing ?: return true
            return !_ui.value.isSavingEdit && text != editing.originalText
        }

    // chat-timeline

    /**
     * Action loadHistory: back-paginates 20 events at a time until the rows have HISTORY_FILL more messages,
     * at most HISTORY_PAGES_PER_LOAD pages. A failed page is tried again. Does nothing at the start or while a load runs.
     */
    fun loadHistory() {
        scope.launch { paginate(HISTORY_PAGES_PER_LOAD) }
    }

    private suspend fun paginate(maxPages: Int) {
        val timeline = timeline ?: return
        if (_reachedStart.value || _isLoadingHistory.value) return
        _isLoadingHistory.value = true
        try {
            val before = _messages.value.size
            for (page in 1..maxPages) {
                val hitStart = paginateBackwards(timeline) ?: return
                if (hitStart) {
                    _reachedStart.value = true
                    break
                }
                // The new items reach rows through the timeline listener, so this count can lag one page.
                if (_messages.value.size - before >= HISTORY_FILL) break
            }
            _historyLoads.update { it + 1 }
        } finally {
            _isLoadingHistory.value = false
        }
    }

    /** One page back, tried HISTORY_TRIES times. null when every try failed. */
    private suspend fun paginateBackwards(timeline: Timeline): Boolean? {
        for (attempt in 1..HISTORY_TRIES) {
            runCatching { timeline.paginateBackwards(HISTORY_PAGE) }.onSuccess { return it }
            if (attempt < HISTORY_TRIES) delay(attempt * 1000L)
        }
        return null
    }

    /** Action editDraft, with typing-indicator draftChanged. */
    fun editDraft(text: String) {
        _draft.value = text
        if (_ui.value.editing != null) return
        // Action leave of spec composer, also for the app going to the background: the draft is kept 1 s after the last change.
        draftSave?.cancel()
        draftSave = scope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            room?.keepDraft(text)
        }
        // Spec composer editText: pills that left the text go; an @word in a group filters the members.
        val query = if (facts.isDm) null else ComposerMarkdown.mentionQuery(text)
        _composer.update {
            it.copy(
                mentions = ComposerMarkdown.mentions(it.mentions, text),
                suggestions = query?.let { q -> mentionDirectory.suggestions(q, strings.label(R.string.bipe_app_composer_mention_everyone)) } ?: emptyList(),
            )
        }
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
        // Spec composer send: Markdown with the pills as matrix.to links, then the mentions.
        val mentions = _composer.value.mentions
        draftSave?.cancel()
        _draft.value = ""
        _composer.update { it.copy(mentions = emptyList(), suggestions = emptyList()) }
        sendTyping(false)
        val replyingTo = _ui.value.replyingTo
        _ui.update { it.copy(replyingTo = null, replyingToRowId = null) }
        val room = room
        scope.launch {
            room?.let { runCatching { it.clearComposerDraft(null) } }
            runCatching {
                val content = ComposerMarkdown.content(text, mentions)
                if (replyingTo != null) timeline.sendReply(content, replyingTo.eventId) else timeline.send(content)
            }
        }
    }

    /** Action leave of spec composer: keeps the draft on this device, or clears it. */
    fun leave() {
        val room = room ?: return
        if (_ui.value.editing != null) return
        val text = _draft.value
        // Runs also when the screen scope is gone (the chat closed).
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { room.keepDraft(text) }
    }

    /** Action pickSuggestion of spec composer: the @word becomes a pill. */
    fun pickSuggestion(suggestion: MentionSuggestion) {
        val (text, mention) = ComposerMarkdown.insert(suggestion, _draft.value, strings.label(R.string.bipe_app_composer_mention_everyone))
        _draft.value = text
        _composer.update { it.copy(mentions = it.mentions + mention, suggestions = emptyList()) }
    }

    /** The client, for upload-preview and sticker-picker. */
    val client get() = session.client

    /** The reply target for a media, voice or sticker send; the reply bar closes. */
    fun takeReplyEventId(): String? {
        val eventId = _ui.value.replyingTo?.eventId
        _ui.update { it.copy(replyingTo = null, replyingToRowId = null) }
        return eventId
    }

    /** Action startRecording of spec composer. */
    fun startRecording(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val timeline = timeline ?: return
        if (_composer.value.recording != null) return
        _composer.update { it.copy(recording = VoiceRecordingModel(context, timeline, takeReplyEventId(), scope)) }
    }

    /** Back to the text field after the recording was sent or deleted. */
    fun endRecording() {
        _composer.update { it.copy(recording = null) }
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
        if (!acceptsMenuActions) return false
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
        if (row.kind == TimelineRow.Kind.UNDECRYPTABLE) {
            // Undecryptable rows have no MessageDetails. Resolve the original SDK event instead.
            val eventId = item(row.id)?.asEvent()?.let(TimelineRowBuilder::eventId)
            val isSent = message.sendState == TimelineMessage.SendState.SENT && eventId != null
            return if (!message.isOwn && isSent) listOf(MessageAction.REPORT) else emptyList()
        }
        val isSent = message.sendState == TimelineMessage.SendState.SENT && row.details?.eventId != null
        val details = row.details ?: return emptyList()
        if (row.kind != TimelineRow.Kind.MESSAGE) return emptyList()
        return MessageAction.entries.filter { action ->
            when (action) {
                MessageAction.REPLY -> details.canReply && isSent
                MessageAction.EDIT -> details.canEdit
                // Media copies its caption, voice its transcript (specs timeline-media, voice-message).
                MessageAction.COPY -> !message.body.isNullOrBlank()
                // Spec message-actions notes (ADR 0164): sent voice messages whose audio is still on the server.
                MessageAction.KEEP_AUDIO -> keepable(message, isSent) && message.voice!!.source.url !in kept()
                MessageAction.UNKEEP_AUDIO -> message.voice?.source?.url?.let { it in kept() } ?: false
                MessageAction.RETRY -> message.isOwn && message.sendState == TimelineMessage.SendState.FAILED
                MessageAction.DELETE -> details.canDelete || (message.isOwn && !isSent)
                MessageAction.REPORT -> !message.isOwn && isSent
            }
        }
    }

    private fun kept(): Map<String, BipeKeptMedia.Reason> = voiceMedia?.kept?.value ?: emptyMap()

    private fun keepable(message: TimelineMessage, isSent: Boolean): Boolean {
        val voice = message.voice ?: return false
        return voiceMedia != null && voice.isVoice && isSent && !VoiceMediaExpiry.isExpired(voice.source.url)
    }

    /** Spec message-actions showsReactions: messages that take reactions (message-reactions notes). */
    fun showsReactions(row: TimelineRow): Boolean =
        row.kind == TimelineRow.Kind.MESSAGE && row.details?.eventId != null && row.message?.sendState == TimelineMessage.SendState.SENT

    private val _moneyPause = MutableStateFlow(BipeMoneyPause())
    /** Spec scam-warning money pause: one per message while this chat is open. */
    val moneyPause: StateFlow<BipeMoneyPause> = _moneyPause.asStateFlow()

    /** Spec scam-warning startPause: the first time the card of a money kind shows. Returns `true` when it started. */
    fun startMoneyPause(rowId: String, kind: ScamWarningKind): Boolean {
        val before = _moneyPause.value
        val after = before.started(rowId, kind)
        _moneyPause.value = after
        return after != before
    }

    /** Seconds before Copiar works again on this row (0: no pause). */
    fun pauseSecondsLeft(rowId: String, nowMs: Long = System.currentTimeMillis()): Int = _moneyPause.value.secondsLeft(rowId, nowMs)

    /** Action choose. copy puts the text on the clipboard through the screen. */
    fun choose(action: MessageAction, rowId: String, copy: (String) -> Unit) {
        closeActions()
        val current = row(rowId) ?: return
        if (!acceptsMenuActions || action !in actions(current)) return
        when (action) {
            MessageAction.REPLY -> startReply(rowId)
            MessageAction.EDIT -> startEdit(rowId)
            MessageAction.COPY -> if (pauseSecondsLeft(rowId) == 0) row(rowId)?.message?.body?.let {
                copy(it)
                show(TimelineNotice.COPIED)
            }
            MessageAction.KEEP_AUDIO, MessageAction.UNKEEP_AUDIO -> {
                val store = voiceMedia ?: return
                val url = current.message?.voice?.source?.url ?: return
                scope.launch {
                    if (action == MessageAction.UNKEEP_AUDIO) {
                        if (!store.unkeep(url)) show(TimelineNotice.KEEP_FAILED)
                        return@launch
                    }
                    when (store.keep(url, BipeKeptMedia.Reason.KEPT)) {
                        VoiceMediaStore.KeepResult.DONE -> Unit
                        VoiceMediaStore.KeepResult.LIMIT -> show(TimelineNotice.KEEP_LIMIT)
                        VoiceMediaStore.KeepResult.FAILED -> show(TimelineNotice.KEEP_FAILED)
                    }
                }
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
                paginate(maxPages = 1)
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
        val current = row(rowId) ?: return
        if (!acceptsMenuActions || !showsReactions(current)) return
        closeActions()
        toggleReaction(rowId, key)
    }

    /** Action moreReactions of message-actions. */
    fun moreReactions(rowId: String) {
        val current = row(rowId) ?: return
        if (!acceptsMenuActions || !showsReactions(current)) return
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

    // handle-change-line

    /**
     * Spec handle-change-line: the cached changes of the room's members now, then a fetch of new ones.
     * A failed fetch keeps the cache and tries again on the next open.
     */
    private suspend fun loadHandleChanges(room: Room) {
        val store = handleChanges ?: return
        val members = GroupPeople.members(room, cachedOnly = true) ?: return
        val joined = members.filter { it.membership is MembershipState.Join }
        val names = joined.filter { it.userId !in facts.members }.associate { member ->
            member.userId to MemberInfo(member.displayName?.takeIf { it.isNotEmpty() } ?: member.userId, member.avatarUrl)
        }
        val roomMembers = joined.map { it.userId }.toSet()
        facts = facts.copy(roomMembers = roomMembers, members = facts.members + names, handleChanges = store.changes(roomMembers))
        scope.launch {
            store.changes.collect {
                facts = facts.copy(handleChanges = store.changes(facts.roomMembers))
                rebuild()
            }
        }
        scope.launch { store.refresh(roomMembers) }
    }

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

    /**
     * peerAway: Room.member of the DM's other person; a leave or ban is LEFT, and a profile that is not found
     * (a deactivated account) is ACCOUNT_GONE. The profile lookup runs once per leave.
     */
    private suspend fun loadPeerAway(room: Room, peer: String?) {
        val member = peer?.let { runCatching { room.member(it) }.getOrNull() }
        if (peer == null || member == null) {
            _peerAway.value = PeerAway.NONE
            return
        }
        when (member.membership) {
            is MembershipState.Leave, is MembershipState.Ban -> {
                if (accountGoneChecked?.first != peer) accountGoneChecked = peer to accountIsGone(peer)
                _peerAway.value = if (accountGoneChecked?.second == true) PeerAway.ACCOUNT_GONE else PeerAway.LEFT
            }
            else -> {
                accountGoneChecked = null
                _peerAway.value = PeerAway.NONE
            }
        }
    }

    private suspend fun accountIsGone(userId: String): Boolean = try {
        session.client.getProfile(userId)
        false
    } catch (error: ClientException.MatrixApi) {
        error.kind is ErrorKind.NotFound
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        false
    }

    /**
     * Action inviteAgain of spec chat-timeline: Room.inviteUserById when the user may invite. In a DM created with
     * the DM power levels nobody may, so [createDm] makes a new DM; its room id comes back for the screen to open.
     */
    suspend fun inviteAgain(createDm: suspend (String) -> String): String? {
        val room = room ?: return null
        val peer = _peerUserId.value ?: return null
        if (_peerAway.value != PeerAway.LEFT || _invitingAgain.value) return null
        _invitingAgain.value = true
        return try {
            if (runCatching { room.getPowerLevels().canOwnUserInvite() }.getOrDefault(false)) {
                room.inviteUserById(peer)
                loadPeerAway(room, peer)
                null
            } else {
                createDm(peer)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            show(TimelineNotice.INVITE_AGAIN_FAILED)
            null
        } finally {
            _invitingAgain.value = false
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
        /** One loadHistory pages on until it adds this many message rows (state events give no rows). */
        const val HISTORY_FILL = 10
        /** The most pages one loadHistory requests. */
        const val HISTORY_PAGES_PER_LOAD = 5
        /** The tries of one page before the load stops: a fresh login can fail the first pages during the first sync. */
        const val HISTORY_TRIES = 3
        /** Spec message-reply openOriginal: at most 5 pages back. */
        const val ORIGINAL_SEARCH_PAGES = 5
        /** Spec typing-indicator: typing on at most once every 4 seconds. */
        const val TYPING_INTERVAL_MS = 4000L
        const val DRAFT_SAVE_DELAY_MS = 1000L
    }
}
