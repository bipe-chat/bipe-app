// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.polls

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FilterTimelineEventType
import uniffi.ruma_events.MessageLikeEventType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineEventFilter
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** Spec poll-history (bipe-chat/bipe#669): the polls of a group, newest first. */
class PollHistoryModel(private val session: AccountSession, private val roomId: String, private val scope: CoroutineScope) {
    data class State(val polls: List<PollData> = emptyList(), val isLoading: Boolean = true, val reachedStart: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null
    private val items = mutableListOf<TimelineItem>()
    private var paging = false

    /** Action appear. */
    fun start() {
        if (timeline != null) return
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull()
            val filter = TimelineEventFilter.includeEventTypes(listOf(FilterTimelineEventType.MessageLike(MessageLikeEventType.PollStart)))
            val configuration = TimelineConfiguration(
                focus = TimelineFocus.Live(hideThreadedEvents = false),
                filter = TimelineFilter.EventFilter(filter),
                internalIdPrefix = "polls",
                dateDividerMode = DateDividerMode.DAILY,
                trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                reportUtds = false,
            )
            val timeline = room?.let { runCatching { it.timelineWithConfiguration(configuration) }.getOrNull() }
            if (timeline == null) {
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            this@PollHistoryModel.timeline = timeline
            val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
            handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diffs.trySend(diff)
                }
            })
            launch {
                while (!_state.value.reachedStart && _state.value.polls.size < FIRST_FILL) {
                    if (!loadMore()) break
                }
                _state.value = _state.value.copy(isLoading = false)
            }
            for (batch in diffs) {
                batch.forEach { items.applyDiff(it.toVectorDiff()) }
                rebuild()
            }
        }
    }

    /** Action loadMore. false when the page failed. */
    suspend fun loadMore(): Boolean {
        val timeline = timeline ?: return true
        if (_state.value.reachedStart || paging) return true
        paging = true
        val hitStart = runCatching { timeline.paginateBackwards(PAGE) }.getOrNull()
        paging = false
        if (hitStart == null) return false
        _state.value = _state.value.copy(reachedStart = hitStart)
        return true
    }

    fun loadMoreLater() {
        scope.launch { loadMore() }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    private fun rebuild() {
        val polls = items.asReversed().mapNotNull { item ->
            val event = item.asEvent() ?: return@mapNotNull null
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return@mapNotNull null
            val poll = ((event.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Poll) ?: return@mapNotNull null
            PollData.of(eventId, poll, event.isOwn, session.userId)
        }
        _state.value = _state.value.copy(polls = polls)
    }

    private companion object {
        /** Action appear loads pages until this many polls or the start of the group. */
        const val FIRST_FILL = 20
        const val PAGE: UShort = 50u
    }
}

/** Spec poll-history. */
@Composable
fun PollHistoryScreen(session: AccountSession, roomId: String, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { PollHistoryModel(session, roomId, scope) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.POLL_HISTORY.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_poll_history), onBack = onBack)
            if (state.polls.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoading) {
                        CircularProgressIndicator(color = colors.accentFill)
                    } else {
                        Text(stringResource(R.string.bipe_poll_history_empty), color = colors.inkSecondary)
                    }
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.polls, key = { _, poll -> poll.eventId }) { index, poll ->
                    if (index == state.polls.lastIndex) LaunchedEffect(poll.eventId) { model.loadMoreLater() }
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(poll.question, color = colors.inkPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(if (poll.isEnded) R.string.bipe_poll_state_ended else R.string.bipe_poll_state_open),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (poll.isEnded) colors.inkSecondary else colors.accentInk,
                            )
                            poll.totalVotes?.let {
                                Text(
                                    stringResource(R.string.bipe_app_poll_votes, NumberFormat.getIntegerInstance().format(it)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                        // State leader: the answer with the most votes, when results show and a vote exists.
                        val leader = poll.options.takeIf { poll.resultsVisible }?.maxByOrNull { it.voteCount ?: 0 }?.takeIf { (it.voteCount ?: 0) > 0 }
                        if (leader != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.bipe_app_poll_most_votes), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.inkSecondary)
                                Text(leader.text, style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
