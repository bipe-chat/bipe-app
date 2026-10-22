// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.InviterGreetings
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.media.MediaContent
import chat.bipe.app.media.MediaFormat
import chat.bipe.app.media.MediaMessage
import chat.bipe.app.media.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.EncryptedMessage
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import org.matrix.rustcomponents.sdk.messageEventContentNew
import uniffi.matrix_sdk_crypto.UtdCause

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
    val media: MediaMessage? = null,
    val voice: VoiceMessage? = null,
) {
    /** MEDIA: image, video, file or sticker (spec timeline-media). VOICE: voice message or audio file (spec voice-message). */
    enum class Kind { TEXT, MEDIA, VOICE, UNDECRYPTABLE, UNSUPPORTED }

    enum class SendState { SENT, SENDING, FAILED }

    /** Bipe's grouping of the SDK UtdCause (spec message-undecryptable). */
    enum class UndecryptableCause { WAITING, BEFORE_JOINED, HISTORY_NOT_ON_THIS_PHONE, NOT_SHARED_WITH_THIS_PHONE, UNAPPROVED_SENDER_DEVICE }
}

/** Spec chat-timeline (bipe-shared product/screens/chat-timeline.json). */
class ChatTimelineModel(
    private val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
    private val greetings: InviterGreetings,
) {
    private val _roomName = MutableStateFlow("")
    val roomName: StateFlow<String> = _roomName.asStateFlow()
    private val _messages = MutableStateFlow<List<TimelineMessage>>(emptyList())
    val messages: StateFlow<List<TimelineMessage>> = _messages.asStateFlow()
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    private val _reachedStart = MutableStateFlow(false)
    val reachedStart: StateFlow<Boolean> = _reachedStart.asStateFlow()
    private val _isPrivateRoom = MutableStateFlow(true)
    /** A DM or a group that needs an invite, for the show-media setting (spec timeline-media). */
    val isPrivateRoom: StateFlow<Boolean> = _isPrivateRoom.asStateFlow()
    /** signup-done: the inviter of this account while this chat with them has no first message (ADR 0034). */
    private val _inviterHint = MutableStateFlow<InviterGreetings.Greeting?>(null)
    val inviterHint: StateFlow<InviterGreetings.Greeting?> = _inviterHint.asStateFlow()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private var timeline: Timeline? = null
    private val items = mutableListOf<TimelineItem>()
    private var handle: TaskHandle? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            val timeline = runCatching { room.timeline() }.getOrNull() ?: return@launch
            _roomName.value = room.displayName() ?: roomId
            _isPrivateRoom.value = room.isPublic() != true
            greetings.get()?.let { greeting ->
                if (runCatching { session.client.getDmRoom(greeting.userId) }.getOrNull()?.id() == roomId) _inviterHint.value = greeting
            }
            this@ChatTimelineModel.timeline = timeline
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

    fun close() {
        handle?.cancel()
    }

    /** Action loadHistory: back-paginates 20 events. Does nothing at the start or while a load runs. */
    fun loadHistory() {
        val timeline = timeline ?: return
        if (_reachedStart.value || _isLoadingHistory.value) return
        _isLoadingHistory.value = true
        scope.launch {
            try {
                if (runCatching { timeline.paginateBackwards(HISTORY_PAGE) }.getOrDefault(false)) {
                    _reachedStart.value = true
                }
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }

    /** Action editDraft. */
    fun editDraft(text: String) {
        _draft.value = text
    }

    val canSend: Boolean get() = _draft.value.isNotBlank()

    /** Action send: plain text through the timeline, then clears the draft. The local echo shows as sending. */
    fun send() {
        val text = _draft.value.trim()
        val timeline = timeline ?: return
        if (text.isEmpty()) return
        _draft.value = ""
        scope.launch {
            runCatching {
                timeline.send(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))))
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
        val item = items.firstOrNull { it.uniqueId().id == id } ?: return
        val sendHandle = item.asEvent()?.lazyProvider?.getSendHandle() ?: return
        scope.launch { runCatching { sendHandle.tryResend() } }
    }

    /** Action cancelUpload of spec timeline-media: cancels the send of own media and removes the row. */
    fun cancelUpload(id: String) {
        val item = items.firstOrNull { it.uniqueId().id == id } ?: return
        val sendHandle = item.asEvent()?.lazyProvider?.getSendHandle() ?: return
        scope.launch { runCatching { sendHandle.abort() } }
    }

    private fun rebuild() {
        if (items.any { it.asVirtual() is VirtualTimelineItem.TimelineStart }) _reachedStart.value = true
        val messages = items.mapNotNull { item -> item.asEvent()?.let { message(item.uniqueId().id, it) } }
        _messages.value = messages
        val hint = _inviterHint.value ?: return
        if (messages.any { it.isOwn && it.kind != TimelineMessage.Kind.UNSUPPORTED }) {
            // The first message to the inviter ends the greeting for this account.
            greetings.clear()
            _inviterHint.value = null
        } else if (messages.any { it.senderId == hint.userId && it.kind != TimelineMessage.Kind.UNSUPPORTED }) {
            _inviterHint.value = null
        }
    }

    private fun message(id: String, event: EventTimelineItem): TimelineMessage {
        var kind = TimelineMessage.Kind.UNSUPPORTED
        var body: String? = null
        var cause: TimelineMessage.UndecryptableCause? = null
        var media: MediaMessage? = null
        var voice: VoiceMessage? = null
        val content = (event.content as? TimelineItemContent.MsgLike)?.content
        when (val found = content?.kind?.let { MediaContent.of(it) }) {
            is MediaContent.Media -> {
                kind = TimelineMessage.Kind.MEDIA
                media = found.media.copy(uploadProgress = if (event.isOwn) MediaFormat.uploadPercent(event.localSendState) else null)
            }
            is MediaContent.Voice -> {
                kind = TimelineMessage.Kind.VOICE
                voice = found.voice
            }
            null -> Unit
        }
        when (val msgKind = content?.kind) {
            is MsgLikeKind.Message -> if (msgKind.content.msgType is MessageType.Text) {
                kind = TimelineMessage.Kind.TEXT
                body = msgKind.content.body
            }
            is MsgLikeKind.UnableToDecrypt -> {
                kind = TimelineMessage.Kind.UNDECRYPTABLE
                cause = undecryptableCause(msgKind.msg)
            }
            else -> Unit
        }
        val sendState = when (event.localSendState) {
            is EventSendState.NotSentYet -> TimelineMessage.SendState.SENDING
            is EventSendState.SendingFailed -> TimelineMessage.SendState.FAILED
            else -> TimelineMessage.SendState.SENT
        }
        return TimelineMessage(
            id = id,
            kind = kind,
            body = body,
            senderId = event.sender,
            senderName = event.senderProfile.displayName(event.sender),
            isOwn = event.isOwn,
            sentAt = event.timestamp.toLong(),
            sendState = if (event.isOwn) sendState else TimelineMessage.SendState.SENT,
            cause = cause,
            media = media,
            voice = voice,
        )
    }

    /** Cause map of spec message-undecryptable (notes). */
    private fun undecryptableCause(encrypted: EncryptedMessage): TimelineMessage.UndecryptableCause {
        val megolm = encrypted as? EncryptedMessage.MegolmV1AesSha2 ?: return TimelineMessage.UndecryptableCause.WAITING
        return when (megolm.cause) {
            UtdCause.SENT_BEFORE_WE_JOINED -> TimelineMessage.UndecryptableCause.BEFORE_JOINED
            UtdCause.HISTORICAL_MESSAGE_AND_BACKUP_IS_DISABLED,
            UtdCause.HISTORICAL_MESSAGE_AND_DEVICE_IS_UNVERIFIED,
            -> TimelineMessage.UndecryptableCause.HISTORY_NOT_ON_THIS_PHONE
            UtdCause.WITHHELD_BY_SENDER,
            UtdCause.WITHHELD_FOR_UNVERIFIED_OR_INSECURE_DEVICE,
            -> TimelineMessage.UndecryptableCause.NOT_SHARED_WITH_THIS_PHONE
            UtdCause.VERIFICATION_VIOLATION,
            UtdCause.UNSIGNED_DEVICE,
            -> TimelineMessage.UndecryptableCause.UNAPPROVED_SENDER_DEVICE
            else -> TimelineMessage.UndecryptableCause.WAITING
        }
    }

    private companion object {
        const val HISTORY_PAGE: UShort = 20u
    }
}
