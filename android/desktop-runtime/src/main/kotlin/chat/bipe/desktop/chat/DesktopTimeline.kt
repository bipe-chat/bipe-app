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
import chat.bipe.desktop.safety.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

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
    private val safetyEngine: SafetyEngine = CoreSafety,
) : AccountResource {
    private val mutableRows = MutableStateFlow<LoadPhase<List<MessageRow>>>(LoadPhase.Loading)
    val rows = mutableRows.asStateFlow()
    val composer = runtime.drafts.room(roomId)
    private val mutableSend = MutableStateFlow<SendPhase>(SendPhase.Idle)
    val sendPhase = mutableSend.asStateFlow()
    private val paging = Mutex()
    private lateinit var timeline: Timeline
    private lateinit var room: Room
    val reactions = ReactionActions(group, ::currentRow) { eventId, key ->
        timeline.toggleReaction(EventOrTransactionId.EventId(eventId), key)
        Unit
    }
    private val failedSends = mutableMapOf<String, SendHandle>()
    private val retrying = mutableSetOf<String>()
    private val pause = MoneyPause()
    private val mutablePause = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pauseSeconds = mutablePause.asStateFlow()
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
            val assessment = CoreSafety.boundary({ safetyEngine.warning(row.body) }, { MessageSafety.Unavailable })
            group.publish {
                val state = mutableRows.value as? LoadPhase.Ready ?: return@publish
                mutableRows.value = LoadPhase.Ready(state.value.map {
                    if (it.id == rowId && it.body == row.body && it.safety == MessageSafety.Checking) it.copy(safety = assessment) else it
                })
            }
        }
    }

    private fun currentRow(id: String): MessageRow? = when (val state = mutableRows.value) {
        is LoadPhase.Ready -> state.value.firstOrNull { it.id == id }
        else -> null
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

    fun draftChanged(value: String) { composer.edit(value) }

    fun send() {
        var submission: DraftSubmission? = null
        group.publish {
            if (mutableSend.value != SendPhase.Sending) {
                submission = composer.submission()
                if (submission == null) return@publish
                mutableSend.value = SendPhase.Sending
            }
        }
        val submitted = submission ?: return
        group.launch {
            try {
                // A send tap authorizes queueing. View cancellation retains this operation's native owner;
                // it must not leave a queued message's old draft behind merely because the room closed.
                withContext(NonCancellable) {
                    val content = messageEventContentFromMarkdown(submitted.text)
                    try { timeline.send(content).destroy() } // Releasing a handle does not abort its SDK queue entry.
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
        try { timeline.paginateBackwards(50u) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Current messages stay visible; the same button retries. */ }
        finally { paging.unlock() }
    }

    override suspend fun close() = group.close()

    private suspend fun start() {
        room = runtime.client.getRoom(roomId) ?: error("Room unavailable")
        group.own(NativeResource { room.destroy() })
        val configuration = TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false), filter = TimelineFilter.All,
            internalIdPrefix = null, dateDividerMode = DateDividerMode.DAILY,
            // Windows has no UTD diagnostic collector. The SDK still decrypts and emits locked-message items.
            trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS, reportUtds = false,
        )
        timeline = room.timelineWithConfiguration(configuration)
        group.own(NativeResource { timeline.destroy() })
        group.own(NativeResource {
            synchronized(failedSends) { failedSends.values.forEach { it.destroy() }; failedSends.clear(); retrying.clear() }
        })
        val changes = Channel<List<TimelineDiff>>(Channel.UNLIMITED, onUndeliveredElement = ::dispose)
        group.own(NativeResource { changes.cancel() })
        group.own(TaskResource(timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                if (changes.trySend(diff).isFailure) dispose(diff)
            }
        })))
        group.launch {
            val snapshots = SnapshotVector<MessageRow>()
            try {
                for (batch in changes) {
                    try {
                        batch.forEach { snapshots.apply(it.toVectorDiff().snapshot(::message)) }
                    } finally { dispose(batch) }
                    group.publish {
                        mutableRows.value = LoadPhase.Ready(snapshots.visible())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { mutableRows.value = LoadPhase.Failed } }
        }
        group.launch {
            while (currentCoroutineContext().isActive) {
                delay(250)
                group.publish { mutablePause.value = pause.remaining(MoneyPause.nowMs()) }
            }
        }
        // Back-pagination failure is retryable; the live listener remains attached for offline/local events.
        loadEarlier()
    }

    private fun message(item: TimelineItem): MessageRow? {
        val event = item.asEvent() ?: return null
        try {
            val content = (event.content as? TimelineItemContent.MsgLike)?.content ?: return null
            val kind = content.kind
            val type = when (kind) {
                is MsgLikeKind.Message -> if (kind.content.msgType is MessageType.Text || kind.content.msgType is MessageType.Notice ||
                                               kind.content.msgType is MessageType.Emote) MessageRow.Kind.TEXT else MessageRow.Kind.UNSUPPORTED
                is MsgLikeKind.UnableToDecrypt -> MessageRow.Kind.UNDECRYPTABLE
                else -> MessageRow.Kind.UNSUPPORTED
            }
            val body = when (type) {
                MessageRow.Kind.TEXT -> (kind as MsgLikeKind.Message).content.body
                MessageRow.Kind.UNDECRYPTABLE -> labels.undecryptable
                MessageRow.Kind.UNSUPPORTED -> labels.unsupported
            }
            val profile = event.senderProfile as? ProfileDetails.Ready
            val delivery = when (event.localSendState) {
                is EventSendState.NotSentYet -> MessageRow.Delivery.SENDING
                is EventSendState.SendingFailed -> MessageRow.Delivery.FAILED
                else -> MessageRow.Delivery.SENT
            }
            val id = item.uniqueId().id
            val reactionTarget = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
                ?.takeIf { event.isRemote && event.localSendState == null && kind !is MsgLikeKind.UnableToDecrypt && kind != MsgLikeKind.Redacted }
            val handle = if (event.isOwn && delivery == MessageRow.Delivery.FAILED) event.lazyProvider.getSendHandle() else null
            synchronized(failedSends) {
                failedSends.remove(id)?.destroy()
                if (handle != null) failedSends[id] = handle
            }
            return MessageRow(id, realName(profile?.displayName, event.sender, labels.noName),
                event.isOwn, body, type, event.timestamp.toLong(), delivery, canRetry = handle != null,
                safety = messageSafety(body, event.isOwn, type == MessageRow.Kind.TEXT, safetyEngine),
                eventId = reactionTarget,
                reactions = if (reactionTarget == null) emptyList() else reactionSnapshots(content.reactions, runtime.client.userId()))
        } finally { event.lazyProvider.destroy() }
    }

    companion object {
        suspend fun open(runtime: MatrixAccountRuntime, roomId: String, labels: ChatLabels): DesktopTimeline {
            val group = AccountTaskGroup.create(runtime.lifetime)
            val model = DesktopTimeline(runtime, group, roomId, labels)
            try { group.async { model.start() }.await(); return model }
            catch (error: Exception) { group.close(); throw error }
        }

        private fun dispose(updates: List<TimelineDiff>) {
            updates.forEach { it.toVectorDiff().forEachValue { item -> item.destroy() } }
        }
    }
}
