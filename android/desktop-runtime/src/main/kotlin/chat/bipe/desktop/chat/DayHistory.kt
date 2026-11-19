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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineEventFocusThreadMode
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

internal suspend fun AccountTaskGroup.observeTimeline(
    timeline: Timeline,
    snapshot: (TimelineItem) -> TimelineSnapshot?,
    updated: suspend (List<TimelineSnapshot>) -> Unit,
    failed: () -> Unit,
) {
    val changes = Channel<List<TimelineDiff>>(Channel.UNLIMITED, onUndeliveredElement = ::disposeTimelineDiffs)
    own(NativeResource { changes.cancel() })
    own(TaskResource(timeline.addListener(object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            if (changes.trySend(diff).isFailure) disposeTimelineDiffs(diff)
        }
    })))
    launch {
        val snapshots = SnapshotVector<TimelineSnapshot>()
        try {
            for (batch in changes) {
                try { batch.forEach { snapshots.apply(it.toVectorDiff().snapshot { item -> snapshot(item) }) } }
                finally { disposeTimelineDiffs(batch) }
                updated(snapshots.visible())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(failed) }
    }
}

private fun disposeTimelineDiffs(updates: List<TimelineDiff>) {
    updates.forEach { it.toVectorDiff().forEachValue { item -> item.destroy() } }
}

/** Separate read-only SDK timeline. It owns no composer, typing or receipt state. */
class DayHistory private constructor(private val owner: AccountTaskGroup, private val timeline: Timeline) : AccountResource {
    enum class Paging { IDLE, LOADING, FAILED }
    data class Pages(val earlier: Boolean = true, val later: Boolean = true, val status: Paging = Paging.IDLE)

    private val mutableRows = MutableStateFlow<LoadPhase<List<MessageRow>>>(LoadPhase.Loading)
    val rows = mutableRows.asStateFlow()
    private val mutablePages = MutableStateFlow(Pages())
    val pages = mutablePages.asStateFlow()

    fun paginate(earlier: Boolean) {
        var accepted: Pages? = null
        owner.publish {
            val current = mutablePages.value
            if (current.status == Paging.LOADING || !(if (earlier) current.earlier else current.later)) return@publish
            accepted = current
            mutablePages.value = current.copy(status = Paging.LOADING)
        }
        val before = accepted ?: return
        owner.launch {
            try {
                val exhausted = if (earlier) timeline.paginateBackwards(50u) else timeline.paginateForwards(50u)
                owner.publish {
                    mutablePages.value = if (earlier) before.copy(earlier = !exhausted, status = Paging.IDLE)
                        else before.copy(later = !exhausted, status = Paging.IDLE)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { owner.publish { mutablePages.value = before.copy(status = Paging.FAILED) } }
        }
    }

    override suspend fun close() = owner.close()

    companion object {
        internal suspend fun open(runtime: MatrixAccountRuntime, roomId: String, focus: DayJump.History,
                                  snapshot: (TimelineItem) -> TimelineSnapshot?): DayHistory =
            open(runtime, roomId, TimelineFocus.Event(focus.eventId, 40u, TimelineEventFocusThreadMode.Automatic(false)),
                TimelineFilter.All, snapshot)

        internal suspend fun open(runtime: MatrixAccountRuntime, roomId: String, focus: TimelineFocus,
                                  filter: TimelineFilter, snapshot: (TimelineItem) -> TimelineSnapshot?): DayHistory {
            val owner = AccountTaskGroup.create(runtime.lifetime)
            try {
                return owner.async {
                    val room = runtime.client.getRoom(roomId) ?: error("Room unavailable")
                    owner.own(NativeResource { room.destroy() })
                    val timeline = room.timelineWithConfiguration(TimelineConfiguration(
                        focus = focus, filter = filter, internalIdPrefix = null, dateDividerMode = DateDividerMode.DAILY,
                        trackReadReceipts = TimelineReadReceiptTracking.DISABLED, reportUtds = false,
                    ))
                    owner.own(NativeResource { timeline.destroy() })
                    val history = DayHistory(owner, timeline)
                    owner.observeTimeline(timeline, snapshot,
                        { rows -> owner.publish { history.mutableRows.value = LoadPhase.Ready(rows.messageRows()) } },
                        { history.mutableRows.value = LoadPhase.Failed })
                    history
                }.await()
            } catch (error: Exception) { owner.close(); throw error }
        }
    }
}
