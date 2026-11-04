// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.media.MediaContent
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** Spec message-pins (bipe-chat/bipe#665): the pinned messages of a group, newest first. */
class PinnedMessagesModel(
    private val session: AccountSession,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val strings: Strings,
) {
    /** The texts the model writes into a pin. */
    data class Strings(val you: String, val voice: String, val kindLabel: (Int) -> String)

    /** Type Pin. */
    data class Pin(val eventId: String, val senderName: String, val text: String)

    data class State(val pins: List<Pin> = emptyList(), val shownIndex: Int = 0) {
        val shown: Pin? get() = pins.getOrNull(shownIndex) ?: pins.firstOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var handle: TaskHandle? = null
    private val items = mutableListOf<TimelineItem>()

    /** Listens to the pinned-events timeline until [close]. Groups only: the caller checks. */
    fun start() {
        if (handle != null) return
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            val configuration = TimelineConfiguration(
                focus = TimelineFocus.PinnedEvents,
                filter = TimelineFilter.All,
                internalIdPrefix = "pinned",
                dateDividerMode = DateDividerMode.DAILY,
                trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                reportUtds = false,
            )
            val timeline = runCatching { room.timelineWithConfiguration(configuration) }.getOrNull() ?: return@launch
            val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
            handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diffs.trySend(diff)
                }
            })
            for (batch in diffs) {
                batch.forEach { items.applyDiff(it.toVectorDiff()) }
                rebuild()
            }
        }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    /** Action tapBanner: the event to scroll to; the banner moves to the next older pin. */
    fun tapBanner(): String? {
        val current = _state.value
        val pin = current.shown ?: return null
        _state.value = current.copy(shownIndex = if (current.pins.isEmpty()) 0 else (current.shownIndex + 1) % current.pins.size)
        return pin.eventId
    }

    private fun rebuild() {
        val pins = items.asReversed().mapNotNull { item ->
            val event = item.asEvent() ?: return@mapNotNull null
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return@mapNotNull null
            val content = event.content as? TimelineItemContent.MsgLike ?: return@mapNotNull null
            val text = when (val media = MediaContent.of(content.content.kind)) {
                is MediaContent.Media -> media.media.caption?.takeIf { it.isNotBlank() } ?: strings.kindLabel(media.media.kindLabel)
                is MediaContent.Voice -> strings.voice
                null -> (content.content.kind as? MsgLikeKind.Message)?.content?.body ?: return@mapNotNull null
            }
            val sender = if (event.isOwn) strings.you else event.senderProfile.displayName(event.sender)
            Pin(eventId, sender, text.replace('\n', ' '))
        }
        val current = _state.value
        _state.value = State(pins, if (current.shownIndex < pins.size) current.shownIndex else 0)
    }
}

/** Spec message-pins: the banner above the timeline of a group. [jump] scrolls the chat to an event. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedBanner(session: AccountSession, roomId: String, jump: (String) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) {
        PinnedMessagesModel(
            session,
            roomId,
            scope,
            PinnedMessagesModel.Strings(
                you = context.getString(R.string.bipe_app_you),
                voice = context.getString(R.string.bipe_app_media_voice),
                kindLabel = context::getString,
            ),
        )
    }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    var showsList by remember { mutableStateOf(false) }
    val pin = state.shown ?: return
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable { model.tapBanner()?.let(jump) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(PhosphorIcon.CHAT_TEXT.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    if (state.pins.size > 1) {
                        stringResource(R.string.bipe_pinned_banner_count, (state.shownIndex + 1).toString(), state.pins.size.toString())
                    } else {
                        stringResource(R.string.bipe_pinned_banner_title)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentInk,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pin.senderName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.inkSecondary, maxLines = 1)
                    Text(pin.text, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (state.pins.size > 1) {
            TextButton(onClick = { showsList = true }) { Text(stringResource(R.string.bipe_pinned_view_all)) }
        }
    }
    if (showsList) {
        ModalBottomSheet(onDismissRequest = { showsList = false }, containerColor = colors.surfaceRaised) {
            Column(Modifier.navigationBarsPadding().testTag(BipeScreen.MESSAGE_PINS.id)) {
                Text(
                    stringResource(R.string.bipe_pinned_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn {
                    items(state.pins, key = { it.eventId }) { item ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                showsList = false
                                jump(item.eventId)
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(item.senderName, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
                            Text(item.text, color = colors.inkPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
