// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.app.common.displayName
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
import org.matrix.rustcomponents.sdk.MsgLikeKind
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
) {
    enum class Kind { MESSAGE, UNDECRYPTABLE, DELETED, POLL, DATE_SEPARATOR, READ_MARKER, NOT_ENCRYPTED, CHAT_START }
}

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
)

/** Strings the builder puts into rows. */
data class RowStrings(val you: String, val deleted: String, val deletedOwn: String, val undecryptable: String)

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
                    val content = (event.content as? TimelineItemContent.MsgLike)?.content ?: return@forEachIndexed
                    val base = message(id, event, name(event.sender))
                    when (val kind = content.kind) {
                        is MsgLikeKind.Message -> {
                            if (kind.content.msgType !is MessageType.Text) return@forEachIndexed
                            val isSent = event.localSendState.let { it == null || it is EventSendState.Sent }
                            val details = MessageDetails(
                                eventId = eventId(event),
                                reply = content.inReplyTo?.let { reply(it, facts, strings, ::name) },
                                reactions = reactions(content.reactions, facts, ::name),
                                isEdited = kind.content.isEdited,
                                receipt = if (event.isOwn) receipts[index] else null,
                                // The detector runs on the device; the cache keys on the event and the body, so an edit runs it again.
                                scamWarning = if (event.isOwn) null else ScamDetector.warningKind(kind.content.body, eventId(event) ?: id),
                                canReply = event.canBeRepliedTo && isSent,
                                canEdit = event.isEditable,
                                canDelete = if (event.isOwn) facts.canRedactOwn || !isSent else !facts.isDm && facts.canRedactOther,
                            )
                            rows += TimelineRow(id, TimelineRow.Kind.MESSAGE, base.copy(kind = TimelineMessage.Kind.TEXT, body = kind.content.body), details)
                        }
                        is MsgLikeKind.UnableToDecrypt -> rows += TimelineRow(
                            id,
                            TimelineRow.Kind.UNDECRYPTABLE,
                            base.copy(kind = TimelineMessage.Kind.UNDECRYPTABLE, cause = undecryptableCause(kind.msg)),
                        )
                        MsgLikeKind.Redacted -> rows += TimelineRow(id, TimelineRow.Kind.DELETED, base)
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

        if (!facts.isEncrypted) {
            val at = if (rows.firstOrNull()?.kind == TimelineRow.Kind.CHAT_START) 1 else 0
            rows.add(at, TimelineRow("bipe-not-encrypted", TimelineRow.Kind.NOT_ENCRYPTED))
        }
        return BuiltRows(dropEmptyDays(rows), hitStart, unknown)
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
        return kind is MsgLikeKind.Message && kind.content.msgType is MessageType.Text
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
            is MsgLikeKind.Message -> kind.content.body
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
