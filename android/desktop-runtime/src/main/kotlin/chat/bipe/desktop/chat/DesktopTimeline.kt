// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.timeline.StateLines
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.account.NativeResource
import chat.bipe.desktop.account.TaskResource
import chat.bipe.desktop.safety.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import chat.bipe.shared.BipeSyncedSettings
import chat.bipe.shared.media.BipeForwardMark
import chat.bipe.shared.media.BipeImageDescription
import chat.bipe.desktop.settings.MatrixSettingsReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import chat.bipe.shared.generated.BipeRegistries
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import uniffi.matrix_sdk_ui.TimelineEventFocusThreadMode
import uniffi.ruma_events.MessageLikeEventType

sealed interface SendPhase {
    data object Idle : SendPhase
    data object Sending : SendPhase
    data object Failed : SendPhase
}

class DesktopTimeline private constructor(
    private val runtime: MatrixAccountRuntime,
    private val group: AccountTaskGroup,
    val roomId: String,
    private val labels: ChatLabels,
    private val typingPreference: TypingPreference,
    private val delivered: DesktopDelivered?,
    private val permitsReadTick: () -> Boolean,
    private val safetyEngine: SafetyEngine = CoreSafety,
) : AccountResource {
    private val mutableRows = MutableStateFlow<LoadPhase<List<MessageRow>>>(LoadPhase.Loading)
    val rows = mutableRows.asStateFlow()
    val composer = runtime.drafts.room(roomId)
    lateinit var typing: RoomTyping
        private set
    private val presentation = Mutex()
    private var latestSnapshots: List<TimelineSnapshot> = emptyList()
    private var receiptPolicy = DmReceiptPolicy("", false, false)
    private val mutableSend = MutableStateFlow<SendPhase>(SendPhase.Idle)
    val sendPhase = mutableSend.asStateFlow()
    val replies = ReplyActions(group, composer, ::currentRow)
    private val mutableOriginal = MutableStateFlow<OriginalNavigation>(OriginalNavigation.Idle)
    val originalNavigation = mutableOriginal.asStateFlow()
    private enum class ReplyLoad { LOADING, SETTLED }
    private val replyLoads = mutableMapOf<String, ReplyLoad>()
    private val paging = Mutex()
    @Volatile private var reachedStart = false
    private lateinit var timeline: Timeline
    private lateinit var room: Room
    private var isGroup = false
    val forwarding = ForwardActions(runtime.client, group, labels.noName, { timeline }) {
        (mutableRows.value as? LoadPhase.Ready)?.value.orEmpty()
    }
    val attachments = AttachmentActions(runtime.client, group, { room }, { timeline }, composer)
    val voice = VoiceSend(group, { room }, { timeline }, composer)
    val stickers = StickerActions(runtime, group, { room }, ::currentRow)
    val pins = RoomPins(group, labels, ::currentRow)
    val reactions = ReactionActions(group, ::currentRow) { eventId, key ->
        timeline.toggleReaction(EventOrTransactionId.EventId(eventId), key)
        Unit
    }
    val deletion = DeleteActions(group, ::currentRow, object : DeleteBackend {
        override suspend fun canDeleteOwn(): Boolean {
            check(room.membership() == Membership.JOINED)
            val powers = room.getPowerLevels()
            try { return powers.canOwnUserRedactOwn() } finally { powers.destroy() }
        }
        override suspend fun deleteOwn(eventId: String) {
            val event = timeline.getEventTimelineItemByEventId(eventId)
            try {
                val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind
                check(event.isOwn && event.isRemote && event.localSendState == null && kind != null &&
                    kind != MsgLikeKind.Redacted && kind !is MsgLikeKind.UnableToDecrypt)
                check(canDeleteOwn())
                group.requireCurrent()
                timeline.redactEvent(EventOrTransactionId.EventId(eventId), null)
            } finally { event.destroy() }
        }
    })
    val edits = EditActions(group, composer, ::currentRow, { mutableSend.value != SendPhase.Sending }) { eventId, text ->
        check(room.membership() == Membership.JOINED)
        val event = timeline.getEventTimelineItemByEventId(eventId)
        try {
            val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message
            check(event.isOwn && event.isEditable && event.isRemote && event.localSendState == null && kind != null)
            val type = kind.content.msgType
            check(type.isText() || type.hasEditableCaption())
            val powers = room.getPowerLevels()
            try { check(powers.canOwnUserSendMessage(MessageLikeEventType.RoomMessage)) } finally { powers.destroy() }
            group.requireCurrent()
            val content = if (type.hasEditableCaption()) EditedContent.MediaCaption(text.takeIf { it.isNotEmpty() }, null, null)
                else EditedContent.RoomMessage(messageEventContentFromMarkdown(text))
            try { timeline.edit(EventOrTransactionId.EventId(eventId), content) } finally { content.destroy() }
        } finally { event.destroy() }
    }
    private val failedSends = mutableMapOf<String, SendHandle>()
    private val retrying = mutableSetOf<String>()
    private val pause = MoneyPause()
    private val mutablePause = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pauseSeconds = mutablePause.asStateFlow()
    private val mutableMediaPolicy = MutableStateFlow<MediaShowPolicy>(MediaShowPolicy.Unknown)
    /** Spec timeline-media showMedia and fromStranger for this room; a row may load its picture only when this allows it or after a click. */
    val mediaPolicy = mutableMediaPolicy.asStateFlow()
    fun warningShown(rowId: String) {
        group.publish {
            val warning = (currentRow(rowId)?.safety as? MessageSafety.Warning) ?: return@publish
            pause.shown(rowId, warning.kind, MoneyPause.nowMs())
            mutablePause.value = pause.remaining(MoneyPause.nowMs())
        }
    }

    fun retrySafety(rowId: String) {
        var previous: MessageRow? = null
        group.publish {
            val state = mutableRows.value as? LoadPhase.Ready ?: return@publish
            val row = state.value.firstOrNull { it.id == rowId && it.safety == MessageSafety.Unavailable } ?: return@publish
            previous = row
            mutableRows.value = LoadPhase.Ready(state.value.map { if (it.id == rowId) it.copy(safety = MessageSafety.Checking) else it })
        }
        val row = previous ?: return
        group.launch {
            val assessment = CoreSafety.boundary({ safetyEngine.warning(messageSafetyText(row.body, row.richText)) }, { MessageSafety.Unavailable })
            group.publish {
                val state = mutableRows.value as? LoadPhase.Ready ?: return@publish
                mutableRows.value = LoadPhase.Ready(state.value.map {
                    if (it.id == rowId && it.body == row.body && it.richText == row.richText && it.safety == MessageSafety.Checking)
                        it.copy(safety = assessment) else it
                })
            }
        }
    }

    private fun currentRow(id: String): MessageRow? = when (val state = mutableRows.value) {
        is LoadPhase.Ready -> state.value.firstOrNull { it.id == id }
        else -> null
    }

    /** Copy is guarded again at dispatch, including the first frame before the warning's clock starts. */
    fun copyText(id: String): String? {
        var result: String? = null
        group.publish {
            val row = currentRow(id) ?: return@publish
            if (row.kind != MessageRow.Kind.TEXT) return@publish
            if (moneyPauseSeconds(row) == 0) result = row.body
        }
        return result
    }

    fun canOpenLink(rowId: String, url: String): Boolean {
        var allowed = false
        group.publish {
            val row = currentRow(rowId) ?: return@publish
            allowed = row.safety != MessageSafety.Checking && row.safety != MessageSafety.Unavailable &&
                moneyPauseSeconds(row) == 0 && row.richText?.runs?.any { it.link == url } == true
        }
        return allowed
    }

    private fun moneyPauseSeconds(row: MessageRow): Int {
        val warning = row.safety as? MessageSafety.Warning
        return pause.remaining(MoneyPause.nowMs())[row.id]
            ?: if (warning?.kind?.registryId() in BipeRegistries.scamMoneyPauseKinds) BipeRegistries.SCAM_MONEY_PAUSE_SECONDS else 0
    }

    /** The Matrix SDK supplies the permalink, including its routing information. */
    suspend fun messageLink(rowId: String, eventId: String): String? = try {
        group.async {
            if (currentRow(rowId)?.linkEventId != eventId) return@async null
            val info = room.roomInfo()
            val joinedGroup = try { info.membership == Membership.JOINED && !info.isDm && !info.isSpace }
                finally { info.destroy() }
            if (!joinedGroup) return@async null
            group.requireCurrent()
            val link = room.matrixToEventPermalink(eventId)
            group.requireCurrent()
            link.takeIf { currentRow(rowId)?.linkEventId == eventId }
        }.await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun replyDetails(eventId: String): MessageQuote = group.async {
        val details = timeline.loadReplyDetails(eventId)
        try { quote(details) } finally { details.destroy() }
    }.await()

    /** Visible quotes ask the SDK once; errors stay retryable from the quote without a recomposition loop. */
    fun loadReply(rowId: String, retry: Boolean = false) {
        var target: String? = null
        group.publish {
            val row = currentRow(rowId) ?: return@publish
            if (row.reply == null || row.reply.details is MessageQuote.Details.Ready) return@publish
            val eventId = row.eventId ?: return@publish
            val previous = replyLoads[eventId]
            if (previous == null || (retry && previous == ReplyLoad.SETTLED)) {
                replyLoads[eventId] = ReplyLoad.LOADING; target = eventId
            }
        }
        val eventId = target ?: return
        group.launch {
            try { timeline.fetchDetailsForEvent(eventId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish {
                val current = mutableRows.value as? LoadPhase.Ready ?: return@publish
                mutableRows.value = LoadPhase.Ready(current.value.map { row ->
                    if (row.eventId == eventId) row.copy(reply = row.reply?.copy(details = MessageQuote.Details.Unavailable)) else row
                })
            } }
            finally { group.publish { replyLoads[eventId] = ReplyLoad.SETTLED } }
        }
    }

    fun openOriginal(eventId: String, focusedFallback: Boolean = false) {
        var accepted = false
        group.publish {
            if (mutableOriginal.value == OriginalNavigation.Idle) {
                mutableOriginal.value = OriginalNavigation.Searching(eventId); accepted = true
            }
        }
        if (!accepted) return
        group.launch {
            try {
                for (page in 0..5) {
                    val found = (mutableRows.value as? LoadPhase.Ready)?.value?.firstOrNull { it.originalEventId == eventId }
                    if (found != null) {
                        group.publish { mutableOriginal.value = OriginalNavigation.Found(found.id) }
                        delay(1_500)
                        return@launch
                    }
                    if (page == 5) break
                    paging.withLock { if (timeline.paginateBackwards(50u)) reachedStart = true }
                    withTimeoutOrNull(1_000) { rows.first { it is LoadPhase.Ready && it.value.any { row -> row.originalEventId == eventId } } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing original never navigates to another room or loses the draft. */ }
            finally {
                group.publish {
                    mutableOriginal.value = if (focusedFallback && mutableOriginal.value is OriginalNavigation.Searching)
                        OriginalNavigation.Context(eventId) else OriginalNavigation.Idle
                }
            }
        }
    }

    fun consumeContext(expected: OriginalNavigation.Context) = group.publish {
        if (mutableOriginal.value === expected) mutableOriginal.value = OriginalNavigation.Idle
    }

    suspend fun openEventHistory(eventId: String): DayHistory {
        group.requireCurrent()
        return DayHistory.open(runtime, roomId, TimelineFocus.Event(eventId, 40u, TimelineEventFocusThreadMode.Automatic(false)),
            TimelineFilter.All) { snapshot(it, trackFailedSends = false) }
    }

    suspend fun reactionPeople(rowId: String, key: String, ownName: String): List<ReactionPerson> = group.async {
        val senders = currentRow(rowId)?.reactions?.firstOrNull { it.key == key }?.senders.orEmpty()
        val ownId = runtime.client.userId()
        senders.map { sender ->
            val name = if (sender.userId == ownId) ownName else try {
                realName(room.memberDisplayName(sender.userId), sender.userId, labels.noName)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { labels.noName }
            ReactionPerson(name, sender.reactedAt)
        }
    }.await()

    fun retry(id: String) {
        val handle = synchronized(failedSends) {
            val handle = failedSends[id] ?: return
            if (!retrying.add(id)) return
            handle
        }
        group.launch {
            try { handle.tryResend() } // SDK re-checks trust; never withdraw verification/ignore a device here.
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The failed row stays, with its retry action. */ }
            finally { synchronized(failedSends) { retrying.remove(id) } }
        }
    }

    fun draftChanged(value: String) = group.publish {
        if (edits.phase.value == EditPhase.Idle) { composer.edit(value); typing.draftChanged(value.isNotBlank()) }
        else edits.change(value)
    }

    /** A callback belongs to the mode/session shown in its frame, even if a save has since completed. */
    fun draftChanged(value: String, expected: MessageEditDraft?) = group.publish {
        if (expected == null && edits.phase.value == EditPhase.Idle) {
            composer.edit(value); typing.draftChanged(value.isNotBlank())
        } else if (expected != null) edits.change(value, expected)
    }

    fun submit(expected: MessageEditDraft?) = group.publish {
        if (expected != null) edits.save(expected)
        else if (edits.phase.value == EditPhase.Idle) send()
    }

    /** Escape cancels the context in its UI frame, preserving the ordinary draft and any in-flight save. */
    fun cancelContext(expectedEdit: MessageEditDraft?, expectedReply: String?): Boolean {
        var cancelled = false
        group.publish {
            if (expectedEdit != null) cancelled = edits.cancel(expectedEdit)
            else if (edits.phase.value == EditPhase.Idle && expectedReply != null &&
                (composer.state.value as? ComposerState.Editing)?.replyTo == expectedReply) {
                composer.cancelReply(); cancelled = true
            }
        }
        return cancelled
    }

    fun startReply(rowId: String): Boolean {
        if (currentRow(rowId)?.canReply != true || !edits.cancel()) return false
        return replies.start(rowId)
    }

    fun send() {
        if (edits.phase.value != EditPhase.Idle) { edits.save(); return }
        var submission: DraftSubmission? = null
        group.publish {
            if (mutableSend.value != SendPhase.Sending) {
                submission = composer.submission()
                if (submission == null) return@publish
                mutableSend.value = SendPhase.Sending
            }
        }
        val submitted = submission ?: return
        typing.draftChanged(false)
        group.launch {
            try {
                // A send tap authorizes queueing. View cancellation retains this operation's native owner;
                // it must not leave a queued message's old draft behind merely because the room closed.
                withContext(NonCancellable) {
                    // A restored reply may refer to an unloaded event. For loaded events re-read native capability;
                    // the SDK resolves missing originals and never silently downgrades a reply to a new message.
                    submitted.replyTo?.let { target ->
                        check(room.membership() == Membership.JOINED)
                        val event = try { timeline.getEventTimelineItemByEventId(target) }
                            catch (_: Exception) { null }
                        if (event != null) try { check(event.canBeRepliedTo) } finally { event.destroy() }
                    }
                    val content = messageEventContentFromMarkdown(submitted.text)
                    try {
                        if (submitted.replyTo != null) timeline.sendReply(content, submitted.replyTo)
                        else timeline.send(content).destroy() // Releasing a handle does not abort its SDK queue entry.
                    }
                    finally { content.destroy() }
                    composer.accepted(submitted)
                }
                group.publish {
                    mutableSend.value = SendPhase.Idle
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { mutableSend.value = SendPhase.Failed } }
        }
    }

    fun loadEarlier() = group.launch {
        if (!paging.tryLock()) return@launch
        try { if (timeline.paginateBackwards(50u)) reachedStart = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Current messages stay visible; the same button retries. */ }
        finally { paging.unlock() }
    }

    suspend fun jumpToDate(date: LocalDate, zone: ZoneId): DayJump {
        val lookup = group.async {
            if (date.isAfter(LocalDate.now(zone))) return@async DayJump.Empty
            val window = DayWindow(date, zone)
            val found = timestampToEvent(runtime.client, roomId, window.start) ?: return@async DayJump.Empty
            val loaded = (mutableRows.value as? LoadPhase.Ready)?.value.orEmpty()
            window.target(found.eventId, found.timestamp, loaded, reachedStart)
        }
        try { return lookup.await() } finally { lookup.cancel() }
    }

    suspend fun openDayHistory(focus: DayJump.History): DayHistory {
        group.requireCurrent()
        return DayHistory.open(runtime, roomId, focus) { snapshot(it, trackFailedSends = false) }
    }

    suspend fun openGallery(): DayHistory {
        group.requireCurrent()
        return DayHistory.open(runtime, roomId, TimelineFocus.Live(hideThreadedEvents = false),
            TimelineFilter.OnlyMessage(listOf(RoomMessageEventMessageType.IMAGE, RoomMessageEventMessageType.VIDEO,
                RoomMessageEventMessageType.FILE))) { snapshot(it, trackFailedSends = false) }
    }

    override suspend fun close() = group.close()

    private suspend fun start() {
        room = runtime.client.getRoom(roomId) ?: error("Room unavailable")
        group.own(NativeResource { room.destroy() })
        val info = room.roomInfo()
        try { isGroup = !info.isDm && !info.isSpace } finally { info.destroy() }
        val configuration = TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false), filter = TimelineFilter.All,
            internalIdPrefix = null, dateDividerMode = DateDividerMode.DAILY,
            // Windows has no UTD diagnostic collector. The SDK still decrypts and emits locked-message items.
            trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS, reportUtds = false,
        )
        timeline = room.timelineWithConfiguration(configuration)
        group.own(NativeResource { timeline.destroy() })
        deletion.start()
        group.own(NativeResource {
            synchronized(failedSends) { failedSends.values.forEach { it.destroy() }; failedSends.clear(); retrying.clear() }
        })
        group.observeTimeline(timeline, { snapshot(it, trackFailedSends = true) }, { snapshots ->
            presentation.withLock {
                latestSnapshots = snapshots
                val receipts = latestSnapshots.dmReceipts(receiptPolicy)
                group.publish {
                    mutableRows.value = LoadPhase.Ready(latestSnapshots.messageRows().map { it.copy(dmReceipt = receipts[it.id]) })
                }
            }
        }, { mutableRows.value = LoadPhase.Failed })
        group.launch {
            while (currentCoroutineContext().isActive) {
                delay(250)
                group.publish { mutablePause.value = pause.remaining(MoneyPause.nowMs()) }
            }
        }
        typing = group.own(RoomTyping(group, runtime.client.userId(), labels.noName, object : TypingBackend {
            override suspend fun notice(typing: Boolean) { room.typingNotice(typing) }
            override suspend fun name(userId: String) = room.memberDisplayName(userId)
        }, { typingPreference.enabled }))
        group.own(TaskResource(room.subscribeToTypingNotifications(object : TypingNotificationsListener {
            override fun call(typingUserIds: List<String>) { typing.incoming(typingUserIds) }
        })))
        delivered?.let { delivery ->
            group.launch { delivery.acks.collect { acks ->
                presentation.withLock {
                    receiptPolicy = receiptPolicy.copy(acked = acks[roomId].orEmpty().toSet())
                    publishReceipts()
                }
            } }
        }
        group.launch { typingPreference.changes.collect { typing.available(it) } }
        group.launch { edits.phase.collect { if (it != EditPhase.Idle) typing.draftChanged(false) } }
        group.launch {
            val reader = MatrixSettingsReader(runtime.client)
            val preference = ReceiptPreference({ reader.accountData(BipeSyncedSettings.EVENT_TYPE) },
                { runtime.client.accountData(BipeSyncedSettings.EVENT_TYPE) })
            while (currentCoroutineContext().isActive) {
                try {
                    val info = room.roomInfo()
                    val dm = try { info.isDm } finally { info.destroy() }
                    val show = try {
                        if (dm && permitsReadTick()) preference.refresh() else false
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                    presentation.withLock {
                        receiptPolicy = receiptPolicy.copy(ownId = runtime.client.userId(), isDm = dm, showsRead = show)
                        publishReceipts()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { /* No stale room can publish; the next SDK cache refresh retries. */ }
                delay(5_000)
            }
        }
        pins.start(room, timeline) { snapshot(it, trackFailedSends = false) }
        startMediaPolicy()
        // Back-pagination failure is retryable; the live listener remains attached for offline/local events.
        loadEarlier()
    }

    private fun publishReceipts() {
        val receipts = latestSnapshots.dmReceipts(receiptPolicy)
        group.publish {
            val current = mutableRows.value as? LoadPhase.Ready ?: return@publish
            mutableRows.value = LoadPhase.Ready(current.value.map { it.copy(dmReceipt = receipts[it.id]) })
        }
    }

    private fun startMediaPolicy() {
        group.launch {
            runtime.media.preference.collect { setting ->
                val value = setting.current ?: return@collect
                val known = try {
                    val info = room.roomInfo()
                    val dm = try { info.isDm } finally { info.destroy() }
                    MediaShowPolicy.Known(value, dm, room.isPublic() == true)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { MediaShowPolicy.Unknown }
                group.publish { mutableMediaPolicy.value = known }
            }
        }
    }

    private fun snapshot(item: TimelineItem, trackFailedSends: Boolean): TimelineSnapshot? {
        val day = item.asVirtual() as? VirtualTimelineItem.DateDivider
        if (day != null) return TimelineSnapshot.Day(day.ts.toLong())
        message(item, trackFailedSends)?.let { return TimelineSnapshot.Message(it) }
        if (!trackFailedSends) return null
        val event = item.asEvent() ?: return null
        try { return event.readReceipts.keys.takeIf { it.isNotEmpty() }?.let { TimelineSnapshot.ReadPoint(it.toSet()) } }
        finally { event.destroy() }
    }

    private fun message(item: TimelineItem, trackFailedSends: Boolean): MessageRow? {
        val event = item.asEvent() ?: return null
        try {
            val profile = event.senderProfile as? ProfileDetails.Ready
            val sender = realName(profile?.displayName, event.sender, labels.noName)
            val id = item.uniqueId().id
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            if (isGroup) StateLines.line(event)?.let { line ->
                return MessageRow(id, sender, event.isOwn, "", MessageRow.Kind.STATE, event.timestamp.toLong(),
                    MessageRow.Delivery.SENT, originalEventId = eventId, senderId = event.sender,
                    readersAtEvent = event.readReceipts.keys.toSet(), stateLine = line)
            }
            val content = (event.content as? TimelineItemContent.MsgLike)?.content ?: return null
            val kind = content.kind
            val media = MessageMedia.of(kind)
            val type = when {
                kind is MsgLikeKind.Message && kind.content.msgType.isText() -> MessageRow.Kind.TEXT
                media != null -> MessageRow.Kind.MEDIA
                kind is MsgLikeKind.UnableToDecrypt -> MessageRow.Kind.UNDECRYPTABLE
                kind == MsgLikeKind.Redacted -> MessageRow.Kind.DELETED
                else -> MessageRow.Kind.UNSUPPORTED
            }
            val body = when (type) {
                MessageRow.Kind.TEXT -> (kind as MsgLikeKind.Message).content.body
                MessageRow.Kind.MEDIA -> media!!.summary(labels)
                MessageRow.Kind.UNDECRYPTABLE -> labels.undecryptable
                MessageRow.Kind.DELETED -> labels.deleted
                MessageRow.Kind.UNSUPPORTED -> (kind as? MsgLikeKind.Message)?.content?.body ?: labels.unsupported
                MessageRow.Kind.STATE -> error("State uses its own row")
            }
            // The scam warning reads text and media captions (spec timeline-media notes), never a kind label.
            val checksSafety = type == MessageRow.Kind.TEXT || (type == MessageRow.Kind.UNSUPPORTED && kind is MsgLikeKind.Message) || (type == MessageRow.Kind.MEDIA && !media?.caption.isNullOrBlank())
            val richText = if (checksSafety) desktopMessageText(body, (kind as? MsgLikeKind.Message)?.content?.msgType) else null
            val delivery = when (event.localSendState) {
                is EventSendState.NotSentYet -> MessageRow.Delivery.SENDING
                is EventSendState.SendingFailed -> MessageRow.Delivery.FAILED
                else -> MessageRow.Delivery.SENT
            }
            val reactionTarget = eventId
                ?.takeIf { event.isRemote && event.localSendState == null && kind !is MsgLikeKind.UnableToDecrypt && kind != MsgLikeKind.Redacted }
            val debugInfo = if (reactionTarget != null && kind is MsgLikeKind.Message)
                runCatching { event.lazyProvider.debugInfo() }.getOrNull() else null
            val forwardCount = BipeForwardMark.count(debugInfo?.originalJson)
            val forwardSource = when (media?.kind) {
                MessageMedia.Kind.VOICE -> BipeForwardMark.source(true, forwardCount)
                MessageMedia.Kind.AUDIO -> BipeForwardMark.source(false, forwardCount)
                else -> BipeForwardMark.line(forwardCount)
            }
            val canForward = trackFailedSends && debugInfo != null &&
                BipeForwardMark.forwardedContentJson(debugInfo.originalJson, debugInfo.latestEditJson) != null
            val handle = if (trackFailedSends && event.isOwn && delivery == MessageRow.Delivery.FAILED) event.lazyProvider.getSendHandle() else null
            if (trackFailedSends) synchronized(failedSends) {
                failedSends.remove(id)?.destroy()
                if (handle != null) failedSends[id] = handle
            }
            return MessageRow(id, sender,
                event.isOwn, body, type, event.timestamp.toLong(), delivery, canRetry = handle != null,
                safety = messageSafety(messageSafetyText(body, richText), event.isOwn, checksSafety, safetyEngine),
                eventId = reactionTarget,
                reactions = if (reactionTarget == null) emptyList() else reactionSnapshots(content.reactions, runtime.client.userId()),
                canReply = reactionTarget != null && event.canBeRepliedTo,
                reply = content.inReplyTo?.let(::quote), isEdited = (kind as? MsgLikeKind.Message)?.content?.isEdited == true,
                originalEventId = eventId,
                canEdit = event.isOwn && event.isEditable && reactionTarget != null &&
                    (type == MessageRow.Kind.TEXT || (kind as? MsgLikeKind.Message)?.content?.msgType?.hasEditableCaption() == true),
                senderId = event.sender, readersAtEvent = event.readReceipts.keys.toSet(),
                media = media?.let {
                    if (it.kind == MessageMedia.Kind.IMAGE) it.copy(description =
                        BipeImageDescription.read(debugInfo?.latestEditJson) ?: BipeImageDescription.read(debugInfo?.originalJson))
                    else it
                }, richText = richText,
                canForward = canForward, forwardSource = forwardSource,
                isEmote = (kind as? MsgLikeKind.Message)?.content?.msgType is MessageType.Emote)
        } finally { event.destroy() }
    }

    private fun quote(details: InReplyToDetails): MessageQuote {
        val event = details.event()
        try {
            val state = when (event) {
                is EmbeddedEventDetails.Ready -> {
                    val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind
                    val preview = when (kind) {
                        is MsgLikeKind.Message -> MessageMedia.of(kind.content.msgType)?.summary(labels) ?: kind.content.body
                        is MsgLikeKind.Sticker -> labels.mediaLabel(MessageMedia.Kind.STICKER)
                        MsgLikeKind.Redacted -> labels.deleted
                        is MsgLikeKind.UnableToDecrypt -> labels.undecryptable
                        else -> labels.unsupported
                    }
                    val name = if (event.sender == runtime.client.userId()) labels.you
                        else realName((event.senderProfile as? ProfileDetails.Ready)?.displayName, event.sender, labels.noName)
                    MessageQuote.Details.Ready(name, preview)
                }
                is EmbeddedEventDetails.Error -> MessageQuote.Details.Unavailable
                else -> MessageQuote.Details.Loading
            }
            return MessageQuote(details.eventId(), state)
        } finally { event.destroy() }
    }

    companion object {
        suspend fun open(runtime: MatrixAccountRuntime, roomId: String, labels: ChatLabels,
                         typingPreference: TypingPreference = TypingPreference.Hidden,
                         delivered: DesktopDelivered? = null, permitsReadTick: () -> Boolean = { true }): DesktopTimeline {
            val group = AccountTaskGroup.create(runtime.lifetime)
            val model = DesktopTimeline(runtime, group, roomId, labels, typingPreference, delivered, permitsReadTick)
            try { group.async { model.start() }.await(); return model }
            catch (error: Exception) { group.close(); throw error }
        }

    }
}

/** Text, notice and emote are the text rows; every other message type is media or unsupported. */
internal fun MessageType.isText(): Boolean = this is MessageType.Text || this is MessageType.Notice || this is MessageType.Emote

private fun MessageType.hasEditableCaption(): Boolean = this is MessageType.Image || this is MessageType.Video || this is MessageType.File
