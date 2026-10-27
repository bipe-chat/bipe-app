// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.app.common.displayName
import chat.bipe.app.media.MediaContent
import chat.bipe.app.media.MediaFormat
import chat.bipe.app.location.LiveLocationMessage
import chat.bipe.app.location.LocationMessage
import chat.bipe.app.media.MediaMessage
import chat.bipe.shared.media.BipeImageDescription
import chat.bipe.shared.R
import chat.bipe.shared.BipeHandleChangePlacement
import chat.bipe.shared.api.BipeHandleChange
import chat.bipe.shared.calls.BipeCall
import chat.bipe.shared.calls.BipeCallLine
import chat.bipe.shared.calls.BipeCallRing
import java.time.Instant
import org.matrix.rustcomponents.sdk.MembershipChange
import chat.bipe.app.polls.PollData
import chat.bipe.shared.ScamDetector
import chat.bipe.shared.ScamWarningKind
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EncryptedMessage
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.InReplyToDetails
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MessageFormat
import chat.bipe.shared.BipeMessageText
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.Reaction
import org.matrix.rustcomponents.sdk.Receipt
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import uniffi.matrix_sdk_crypto.UtdCause

/** Type TimelineRow of spec timeline-rows. */
data class TimelineRow(
    val id: String,
    val kind: Kind,
    /** kind message, undecryptable and deleted. */
    val message: TimelineMessage? = null,
    /** kind message only. */
    val details: MessageDetails? = null,
    /** kind dateSeparator only: a time on that day, in ms. */
    val day: Long? = null,
    /** kind handleChange only (spec handle-change-line, ADR 0066). */
    val handleChange: HandleChangeLine? = null,
    /** kind call only (spec incoming-call notes). */
    val callText: String? = null,
) {
    enum class Kind { MESSAGE, UNDECRYPTABLE, DELETED, POLL, DATE_SEPARATOR, READ_MARKER, HANDLE_CHANGE, NOT_ENCRYPTED, CHAT_START, CALL }
}

/** Type HandleChangeLine of spec handle-change-line. */
data class HandleChangeLine(val change: BipeHandleChange, val name: String)

/** Type MessageDetails of spec timeline-rows. */
data class MessageDetails(
    val eventId: String?,
    val reply: MessageReply?,
    val reactions: List<MessageReaction>,
    val isEdited: Boolean,
    val receipt: MessageReceipt?,
    /** Messages from other people only (spec scam-warning). */
    val scamWarning: ScamWarningKind? = null,
    val canReply: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

/** Type MessageReply of spec message-reply. */
data class MessageReply(
    val eventId: String,
    val state: State,
    val senderName: String? = null,
    val preview: String? = null,
) {
    enum class State { LOADING, READY, UNAVAILABLE }
}

/** Type MessageReaction of spec message-reactions, with the people who reacted, newest first. */
data class MessageReaction(
    val key: String,
    val count: Int,
    val includesOwn: Boolean,
    val isSending: Boolean,
    val senders: List<ReactionSender>,
)

/** Type ReactionSender of spec message-reactions. */
data class ReactionSender(val userId: String, val name: String, val reactedAt: Long)

/** Type MessageReceipt of spec read-receipts. */
data class MessageReceipt(
    val tick: Tick,
    /** Group only: whose newest receipt is on this message, newest first. */
    val readers: List<ReceiptReader>,
    /** Group only: everyone who read this message or a newer one, newest first (the read-by sheet). */
    val readersUpToHere: List<ReceiptReader>,
    /** Only the newest own message shows its tick label as text. */
    val showsLabel: Boolean,
) {
    enum class Tick { SENDING, SENT, READ, FAILED }
}

/** Type ReceiptReader of spec read-receipts. */
data class ReceiptReader(val userId: String, val name: String, val avatarUrl: String?, val readAt: Long?)

/** A member's display name and avatar in this room. */
data class MemberInfo(val name: String, val avatarUrl: String?)

/** What the row builder needs from the room besides the timeline items. */
data class RoomFacts(
    val ownUserId: String,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = true,
    val joinedMembers: Int = 2,
    val canRedactOwn: Boolean = true,
    val canRedactOther: Boolean = false,
    val members: Map<String, MemberInfo> = emptyMap(),
    /** Event IDs whose reply details this screen asked for once (spec message-reply loadReply). */
    val replyLoadsTried: Set<String> = emptySet(),
    /** Members of the room now, by user ID (spec handle-change-line: no line for anyone else). */
    val roomMembers: Set<String> = emptySet(),
    /** Cached handle changes of the room members (BipeHandleChangeStore), oldest first. */
    val handleChanges: List<BipeHandleChange> = emptyList(),
)

/** Strings the builder puts into rows. */
data class RowStrings(
    val you: String,
    val deleted: String,
    val deletedOwn: String,
    val undecryptable: String,
    /** A string resource, for the kind labels of media (spec timeline-media notes). */
    val label: (Int) -> String = { "" },
    /** A string resource with one argument (the call lines of spec incoming-call). */
    val format: (Int, String) -> String = { _, _ -> "" },
)

class BuiltRows(val rows: List<TimelineRow>, val hitStart: Boolean, val unknownUsers: Set<String>)

/** Builds the rows of spec timeline-rows from the SDK timeline items, oldest first. */
object TimelineRowBuilder {
    fun rows(items: List<TimelineItem>, facts: RoomFacts, strings: RowStrings): BuiltRows {
        val rows = mutableListOf<TimelineRow>()
        var hitStart = false
        val unknown = mutableSetOf<String>()

        fun name(userId: String): String {
            if (userId == facts.ownUserId) return strings.you
            facts.members[userId]?.let { return it.name }
            unknown += userId
            return userId
        }

        fun readers(receipts: Map<String, Receipt>): List<ReceiptReader> {
            if (facts.isDm) return emptyList()
            return receipts
                .filterKeys { it != facts.ownUserId }
                .map { (userId, receipt) -> ReceiptReader(userId, name(userId), facts.members[userId]?.avatarUrl, receipt.timestamp?.toLong()) }
                .sortedByDescending { it.readAt ?: 0L }
        }

        // Read receipts, newest first: who has read this event or a newer one (spec read-receipts notes).
        val readUpToHere = linkedMapOf<String, Receipt>()
        val receipts = mutableMapOf<Int, MessageReceipt>()
        var newestOwnIndex: Int? = null
        for (index in items.indices.reversed()) {
            val event = items[index].asEvent() ?: continue
            for ((userId, receipt) in event.readReceipts) {
                if (userId != facts.ownUserId && userId !in readUpToHere) readUpToHere[userId] = receipt
            }
            if (!event.isOwn || !isShownMessage(event)) continue
            if (newestOwnIndex == null) newestOwnIndex = index
            val tick = when (event.localSendState) {
                is EventSendState.NotSentYet -> MessageReceipt.Tick.SENDING
                is EventSendState.SendingFailed -> MessageReceipt.Tick.FAILED
                else -> {
                    val othersInRoom = maxOf(1, facts.joinedMembers - 1)
                    val isRead = if (facts.isDm) readUpToHere.isNotEmpty() else readUpToHere.size >= othersInRoom
                    if (isRead) MessageReceipt.Tick.READ else MessageReceipt.Tick.SENT
                }
            }
            receipts[index] = MessageReceipt(tick, readers(event.readReceipts), readers(readUpToHere), index == newestOwnIndex)
        }

        items.forEachIndexed { index, item ->
            val id = item.uniqueId().id
            when (val virtual = item.asVirtual()) {
                is VirtualTimelineItem.DateDivider -> rows += TimelineRow(id, TimelineRow.Kind.DATE_SEPARATOR, day = virtual.ts.toLong())
                VirtualTimelineItem.ReadMarker -> rows += TimelineRow(id, TimelineRow.Kind.READ_MARKER)
                VirtualTimelineItem.TimelineStart -> {
                    hitStart = true
                    rows += TimelineRow(id, TimelineRow.Kind.CHAT_START)
                }
                null -> {
                    val event = item.asEvent() ?: return@forEachIndexed
                    (event.content as? TimelineItemContent.RtcNotification)?.let { call ->
                        rows += callRows(id, event, call, items, index, facts, strings, ::name)
                        return@forEachIndexed
                    }
                    val content = (event.content as? TimelineItemContent.MsgLike)?.content ?: return@forEachIndexed
                    val base = message(id, event, name(event.sender))
                    // Media and voice rows (specs timeline-media, voice-message): message rows with their own content.
                    val mediaContent = when (val found = MediaContent.of(content.kind)) {
                        is MediaContent.Media -> MediaContent.Media(
                            found.media.copy(
                                uploadProgress = if (event.isOwn) MediaFormat.uploadPercent(event.localSendState) else null,
                                // The SDK types have no field for the image description; it comes from the event JSON (bipe-chat/bipe#501).
                                description = if (found.media.kind == MediaMessage.Kind.IMAGE) {
                                    BipeImageDescription.read(event.lazyProvider.debugInfo().originalJson)
                                } else {
                                    null
                                },
                            ),
                        )
                        else -> found
                    }
                    when (val kind = content.kind) {
                        is MsgLikeKind.Message, is MsgLikeKind.Sticker -> {
                            val message = when (mediaContent) {
                                is MediaContent.Media -> base.copy(
                                    kind = TimelineMessage.Kind.MEDIA,
                                    media = mediaContent.media,
                                    body = mediaContent.media.caption?.takeIf { it.isNotBlank() },
                                )
                                is MediaContent.Voice -> base.copy(
                                    kind = TimelineMessage.Kind.VOICE,
                                    voice = mediaContent.voice,
                                    body = mediaContent.voice.transcript,
                                )
                                null -> {
                                    if (kind !is MsgLikeKind.Message) return@forEachIndexed
                                    when (val type = kind.content.msgType) {
                                        // Spec location-view: a geo URI the app cannot read stays unsupported.
                                        is MessageType.Location -> {
                                            val location = LocationMessage.of(type.content) ?: return@forEachIndexed
                                            base.copy(kind = TimelineMessage.Kind.LOCATION, location = location, body = strings.label(location.labelRes))
                                        }
                                        // Spec timeline-rows: notices and unknown message types keep their body.
                                        else -> base.copy(
                                            kind = TimelineMessage.Kind.TEXT,
                                            body = kind.content.body,
                                            isEmote = type is MessageType.Emote,
                                            richText = BipeMessageText.parse(kind.content.body, type.bipeHTML()),
                                        )
                                    }
                                }
                            }
                            val isEdited = (kind as? MsgLikeKind.Message)?.content?.isEdited ?: false
                            val isSent = event.localSendState.let { it == null || it is EventSendState.Sent }
                            val details = MessageDetails(
                                eventId = eventId(event),
                                reply = content.inReplyTo?.let { reply(it, facts, strings, ::name) },
                                reactions = reactions(content.reactions, facts, ::name),
                                isEdited = isEdited,
                                receipt = if (event.isOwn) receipts[index] else null,
                                // The detector runs on the device; the cache keys on the event and the body, so an edit runs it again.
                                scamWarning = if (event.isOwn) null else message.body?.let { ScamDetector.warningKind(it + "\n" + message.richText?.safetyText.orEmpty(), eventId(event) ?: id) },
                                canReply = event.canBeRepliedTo && isSent,
                                canEdit = event.isEditable && message.kind == TimelineMessage.Kind.TEXT,
                                canDelete = if (event.isOwn) facts.canRedactOwn || !isSent else !facts.isDm && facts.canRedactOther,
                            )
                            rows += TimelineRow(id, TimelineRow.Kind.MESSAGE, message, details)
                        }
                        is MsgLikeKind.UnableToDecrypt -> rows += TimelineRow(
                            id,
                            TimelineRow.Kind.UNDECRYPTABLE,
                            base.copy(kind = TimelineMessage.Kind.UNDECRYPTABLE, cause = undecryptableCause(kind.msg)),
                        )
                        MsgLikeKind.Redacted -> rows += TimelineRow(id, TimelineRow.Kind.DELETED, base)
                        // Spec location-view, live row: the sender's beacon_info with the beacons the SDK put on it.
                        is MsgLikeKind.LiveLocation -> {
                            val message = base.copy(
                                kind = TimelineMessage.Kind.LIVE_LOCATION,
                                live = LiveLocationMessage.of(kind.content),
                                body = strings.label(R.string.bipe_app_location_live_title),
                            )
                            val isSent = event.localSendState.let { it == null || it is EventSendState.Sent }
                            val details = MessageDetails(
                                eventId = eventId(event),
                                reply = null,
                                reactions = reactions(content.reactions, facts, ::name),
                                isEdited = false,
                                receipt = if (event.isOwn) receipts[index] else null,
                                scamWarning = null,
                                canReply = event.canBeRepliedTo && isSent,
                                canEdit = false,
                                canDelete = false,
                            )
                            rows += TimelineRow(id, TimelineRow.Kind.MESSAGE, message, details)
                        }
                        // A poll (spec poll-detail, groups lane).
                        is MsgLikeKind.Poll -> eventId(event)?.let { pollEventId ->
                            val poll = PollData.of(pollEventId, kind, isOwnPoll = event.isOwn, ownUserId = facts.ownUserId)
                            rows += TimelineRow(id, TimelineRow.Kind.POLL, base.copy(kind = TimelineMessage.Kind.POLL, poll = poll))
                        }
                        // Unsupported events are not rows yet (spec timeline-rows kind unsupported).
                        else -> Unit
                    }
                }
            }
        }

        placeHandleChanges(rows, items, facts, ::name)

        if (!facts.isEncrypted) {
            val at = if (rows.firstOrNull()?.kind == TimelineRow.Kind.CHAT_START) 1 else 0
            rows.add(at, TimelineRow("bipe-not-encrypted", TimelineRow.Kind.NOT_ENCRYPTED))
        }
        return BuiltRows(dropEmptyDays(rows), hitStart, unknown)
    }

    /**
     * Spec handle-change-line: local lines before the first row newer than the change, inside the loaded range.
     * The timeline is live, so a change newer than the last row shows at the end.
     */
    /**
     * Spec incoming-call notes: one line for a ring event and the call memberships after it (up to the next ring),
     * and in a group one line for each member who joined.
     */
    private fun callRows(
        id: String,
        event: EventTimelineItem,
        call: TimelineItemContent.RtcNotification,
        items: List<TimelineItem>,
        index: Int,
        facts: RoomFacts,
        strings: RowStrings,
        name: (String) -> String,
    ): List<TimelineRow> {
        val memberEvents = mutableListOf<Pair<String, Long>>()
        for (item in items.drop(index + 1)) {
            val other = item.asEvent() ?: continue
            if (other.content is TimelineItemContent.RtcNotification) break
            val state = other.content as? TimelineItemContent.State ?: continue
            if ((state.content as? OtherState.Custom)?.eventType == BipeCall.MEMBER_EVENT_TYPE) {
                memberEvents += other.sender to other.timestamp.toLong()
            }
        }
        val line = BipeCallRing.line(facts.isDm, event.sender, event.timestamp.toLong(), call.activeMembers.isNotEmpty(), call.declinedBy, memberEvents)
        val label = strings.label(if (call.callIntent == "video") R.string.bipe_call_video else R.string.bipe_call_voice)
        val text = when (line.outcome) {
            BipeCallLine.Outcome.ONGOING -> strings.label(R.string.bipe_call_ongoing)
            BipeCallLine.Outcome.MISSED -> strings.label(R.string.bipe_call_missed)
            BipeCallLine.Outcome.DECLINED -> strings.label(R.string.bipe_call_declined)
            BipeCallLine.Outcome.ENDED -> "$label, " + strings.format(R.string.bipe_call_duration, (line.minutes ?: 1).toString())
        }
        return listOf(TimelineRow(id, TimelineRow.Kind.CALL, callText = text)) +
            line.joined.map { TimelineRow("$id-joined-$it", TimelineRow.Kind.CALL, callText = strings.format(R.string.bipe_call_joined, name(it))) }
    }

    private fun placeHandleChanges(rows: MutableList<TimelineRow>, items: List<TimelineItem>, facts: RoomFacts, name: (String) -> String) {
        if (facts.handleChanges.isEmpty()) return
        // Join times seen in the loaded items: a change before the person joined gets no line.
        val joinedAt = mutableMapOf<String, Instant>()
        for (item in items) {
            val event = item.asEvent() ?: continue
            val membership = event.content as? TimelineItemContent.RoomMembership ?: continue
            if (membership.change == MembershipChange.JOINED || membership.change == MembershipChange.INVITATION_ACCEPTED) {
                joinedAt[membership.userId] = Instant.ofEpochMilli(event.timestamp.toLong())
            }
        }
        val anchors = rows.map { row -> row.message?.sentAt?.let { Instant.ofEpochMilli(it) } }
        val placements = BipeHandleChangePlacement.placements(facts.handleChanges, anchors, facts.roomMembers, joinedAt, includesEnd = true)
        // Back to front, so earlier indices stay valid; changes at one index keep their time order.
        for ((index, change) in placements.asReversed()) {
            if (change.old == null && change.new == null) continue
            rows.add(index, TimelineRow(change.lineId, TimelineRow.Kind.HANDLE_CHANGE, handleChange = HandleChangeLine(change, name(change.userId))))
        }
    }

    /** A date separator whose day has no row left (only hidden events) goes away. */
    private fun dropEmptyDays(rows: List<TimelineRow>): List<TimelineRow> {
        val result = mutableListOf<TimelineRow>()
        for (row in rows) {
            if (row.kind == TimelineRow.Kind.DATE_SEPARATOR && result.lastOrNull()?.kind == TimelineRow.Kind.DATE_SEPARATOR) result.removeAt(result.lastIndex)
            result += row
        }
        if (result.lastOrNull()?.kind == TimelineRow.Kind.DATE_SEPARATOR) result.removeAt(result.lastIndex)
        return result
    }

    fun isShownMessage(event: EventTimelineItem): Boolean {
        val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind
        if (kind != null && MediaContent.of(kind) != null) return true
        if (kind is MsgLikeKind.LiveLocation) return true
        return kind is MsgLikeKind.Message
    }

    fun eventId(event: EventTimelineItem): String? = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId

    private fun message(id: String, event: EventTimelineItem, senderName: String): TimelineMessage {
        val sendState = when (event.localSendState) {
            is EventSendState.NotSentYet -> TimelineMessage.SendState.SENDING
            is EventSendState.SendingFailed -> TimelineMessage.SendState.FAILED
            else -> TimelineMessage.SendState.SENT
        }
        return TimelineMessage(
            id = id,
            kind = TimelineMessage.Kind.UNSUPPORTED,
            body = null,
            senderId = event.sender,
            senderName = if (event.isOwn) senderName else event.senderProfile.displayName(senderName),
            isOwn = event.isOwn,
            sentAt = event.timestamp.toLong(),
            sendState = if (event.isOwn) sendState else TimelineMessage.SendState.SENT,
            cause = null,
        )
    }

    /** Spec message-reply notes: Unavailable and Pending are loading until loadReply ran once; Error is unavailable. */
    private fun reply(details: InReplyToDetails, facts: RoomFacts, strings: RowStrings, name: (String) -> String): MessageReply {
        val eventId = details.eventId()
        return when (val event = details.event()) {
            is EmbeddedEventDetails.Ready -> {
                val isOwn = event.sender == facts.ownUserId
                MessageReply(
                    eventId,
                    MessageReply.State.READY,
                    senderName = if (isOwn) strings.you else event.senderProfile.displayName(name(event.sender)),
                    preview = replyPreview(event.content, isOwn, strings),
                )
            }
            is EmbeddedEventDetails.Error -> MessageReply(eventId, MessageReply.State.UNAVAILABLE)
            EmbeddedEventDetails.Unavailable -> MessageReply(
                eventId,
                if (eventId in facts.replyLoadsTried) MessageReply.State.UNAVAILABLE else MessageReply.State.LOADING,
            )
            EmbeddedEventDetails.Pending -> MessageReply(eventId, MessageReply.State.LOADING)
        }
    }

    private fun replyPreview(content: TimelineItemContent, isOwn: Boolean, strings: RowStrings): String {
        val kind = (content as? TimelineItemContent.MsgLike)?.content?.kind ?: return ""
        return when (kind) {
            is MsgLikeKind.Message -> when (val media = MediaContent.of(kind.content.msgType)) {
                is MediaContent.Media -> media.media.caption?.takeIf { it.isNotBlank() } ?: strings.label(media.media.kindLabel)
                is MediaContent.Voice -> if (media.voice.isVoice) strings.label(R.string.bipe_app_media_voice) else media.voice.filename
                null -> if (kind.content.msgType is MessageType.Location) strings.label(R.string.bipe_app_location_title) else kind.content.body
            }
            is MsgLikeKind.Sticker -> strings.label(R.string.bipe_app_media_sticker)
            is MsgLikeKind.LiveLocation -> strings.label(R.string.bipe_app_location_live_title)
            MsgLikeKind.Redacted -> if (isOwn) strings.deletedOwn else strings.deleted
            is MsgLikeKind.UnableToDecrypt -> strings.undecryptable
            else -> ""
        }
    }

    private fun reactions(reactions: List<Reaction>, facts: RoomFacts, name: (String) -> String): List<MessageReaction> =
        reactions.map { reaction ->
            MessageReaction(
                key = reaction.key,
                count = reaction.senders.size,
                includesOwn = reaction.senders.any { it.senderId == facts.ownUserId },
                isSending = false,
                senders = reaction.senders
                    .map { ReactionSender(it.senderId, name(it.senderId), it.timestamp.toLong()) }
                    .sortedByDescending { it.reactedAt },
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
}

/** Ignore unknown formatting types, as required by Matrix's plain-body fallback. */
private fun MessageType.bipeHTML(): String? {
    val formatted = when (this) {
        is MessageType.Text -> content.formatted
        is MessageType.Notice -> content.formatted
        is MessageType.Emote -> content.formatted
        else -> null
    }
    return formatted?.takeIf { it.format is MessageFormat.Html }?.body
}
