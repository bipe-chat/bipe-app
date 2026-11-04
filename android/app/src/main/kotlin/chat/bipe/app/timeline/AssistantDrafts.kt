// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.composer.ComposerMarkdown
import chat.bipe.app.composer.keepDraft
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.drafts.BipeAssistantDrafts
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/**
 * Spec drafts (bipe-chat/bipe#866, ADR 0210): the drafts that the user's own assistant wrote in the account's private
 * drafts room. The app reads the room named by the global account data chat.bipe.drafts_room and never creates it.
 * Read at start and when the app comes to the front; the drafts then follow the room timeline live.
 */
object AssistantDraftsStore {
    /** Type AssistantDraft of spec drafts. */
    data class Draft(
        val eventId: String,
        val roomId: String,
        val roomName: String,
        val body: String,
        val replyTo: String?,
        val threadRoot: String?,
        val client: String,
        val createdAtMs: Long,
    )

    /** State drafts (newest first) of the account [userId]; sending and failed name a draft. */
    data class State(
        val userId: String? = null,
        val drafts: List<Draft> = emptyList(),
        val sendingEventId: String? = null,
        val failedEventId: String? = null,
        /** bipe_app_drafts_nothing_sent shows until the first send, edit or discard on this device. */
        val showsNothingSent: Boolean = true,
        /** Spec drafts openDraft: the draft the banner shows per target chat, instead of the newest. */
        val focused: Map<String, String> = emptyMap(),
    ) {
        /** State currentRoomDrafts. */
        fun drafts(roomId: String, userId: String): List<Draft> =
            if (this.userId == userId) drafts.filter { it.roomId == roomId } else emptyList()

        fun banner(roomId: String, userId: String): Draft? {
            val current = drafts(roomId, userId)
            return focused[roomId]?.let { id -> current.firstOrNull { it.eventId == id } } ?: current.firstOrNull()
        }
    }

    private const val PREFS = "assistant_drafts"
    private const val KEY_NOTHING_SENT = "nothing_sent_shown"
    private const val ROOM_KEY = "room_"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    /** Goes up when a drafts room ID becomes known, so the room lists hide it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** User ID to drafts room ID. The chat list, search, share and forward leave the room out. */
    private val roomIds = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptionOwner = UUID.randomUUID().toString()
    private var session: AccountSession? = null
    private var watchedRoomId: String? = null
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null
    private var watchJob: Job? = null
    private val items = mutableListOf<TimelineItem>()
    private var noName = ""
    private var prefsLoaded = false

    fun roomId(userId: String): String? = roomIds[userId]

    fun isDraftsRoom(roomId: String, userId: String): Boolean = roomIds[userId] == roomId

    private fun loadPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (key.startsWith(ROOM_KEY) && value is String) roomIds.putIfAbsent(key.removePrefix(ROOM_KEY), value)
        }
        _state.update { it.copy(showsNothingSent = !prefs.getBoolean(KEY_NOTHING_SENT, false)) }
    }

    /** Reads chat.bipe.drafts_room for an account, without opening its timeline (the room lists of every account). */
    suspend fun learnRoomId(context: Context?, session: AccountSession): String? {
        context?.let { loadPrefs(it.applicationContext) }
        val json = runCatching { session.client.accountData(BipeRegistries.DRAFTS_ACCOUNT_DATA_TYPE) }.getOrNull()
        val roomId = BipeAssistantDrafts.roomId(json) ?: return roomIds[session.userId]
        if (roomIds.put(session.userId, roomId) != roomId) {
            context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(ROOM_KEY + session.userId, roomId)?.apply()
            _revision.update { it + 1 }
        }
        return roomId
    }

    /** Action appear: Client.accountData, then the drafts room timeline. Each item goes through draftFromEvent. */
    fun refresh(context: Context, session: AccountSession) {
        noName = context.getString(R.string.bipe_dm_no_name)
        scope.launch {
            if (this@AssistantDraftsStore.session !== session) {
                stop()
                this@AssistantDraftsStore.session = session
                _state.update { it.copy(userId = session.userId, drafts = emptyList(), focused = emptyMap(), failedEventId = null) }
            }
            val roomId = learnRoomId(context, session) ?: return@launch
            if (this@AssistantDraftsStore.session !== session) return@launch
            if (watchedRoomId == roomId && timeline != null) return@launch
            stop()
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            if (runCatching { room.membership() }.getOrNull() != Membership.JOINED) return@launch
            val timeline = runCatching { room.timeline() }.getOrNull() ?: return@launch
            if (this@AssistantDraftsStore.session !== session) return@launch
            watchedRoomId = roomId
            this@AssistantDraftsStore.timeline = timeline
            session.roomSubscriptions.update(subscriptionOwner, listOf(roomId))
            val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
            handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diffs.trySend(diff)
                }
            })
            watchJob = scope.launch {
                for (batch in diffs) {
                    batch.forEach { items.applyDiff(it.toVectorDiff()) }
                    rebuild(session)
                }
            }
            // The drafts room is small: read back to its start, a few pages at most.
            scope.launch {
                repeat(5) {
                    val hitStart = runCatching { timeline.paginateBackwards(50u) }.getOrNull() ?: return@launch
                    if (hitStart) return@launch
                }
            }
        }
    }

    private fun stop() {
        handle?.cancel()
        handle = null
        watchJob?.cancel()
        watchJob = null
        timeline = null
        watchedRoomId = null
        items.clear()
        _state.update { it.copy(drafts = emptyList(), focused = emptyMap(), failedEventId = null) }
        session?.releaseRoomSubscriptions(subscriptionOwner)
    }

    private fun rebuild(session: AccountSession) {
        val joined = runCatching { session.client.rooms() }.getOrDefault(emptyList())
            .filter { runCatching { it.membership() == Membership.JOINED }.getOrDefault(false) }
            .map { it.id() }
            .toSet()
        val result = items.mapNotNull { item ->
            val event = item.asEvent() ?: return@mapNotNull null
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return@mapNotNull null
            val redacted = (event.content as? TimelineItemContent.MsgLike)?.content?.kind is MsgLikeKind.Redacted
            val shown = BipeAssistantDrafts.fromEventJson(
                event.lazyProvider.debugInfo().originalJson, event.sender, redacted, session.userId, joined,
            ) as? BipeAssistantDrafts.Result.Show ?: return@mapNotNull null
            val draft = shown.draft
            val name = runCatching { session.client.getRoom(draft.roomId)?.displayName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: noName
            Draft(eventId, draft.roomId, name, draft.body, draft.replyTo, draft.threadRoot, draft.client, event.timestamp.toLong())
        }.asReversed()
        val ids = result.map { it.eventId }.toSet()
        _state.update { state ->
            state.copy(
                drafts = result,
                focused = state.focused.filterValues { it in ids },
                failedEventId = state.failedEventId?.takeIf { it in ids },
            )
        }
    }

    /** Action openDraft: the banner of the target chat shows this draft. */
    fun open(draft: Draft) {
        _state.update { it.copy(focused = it.focused + (draft.roomId to draft.eventId)) }
    }

    /**
     * Action send: body as the user's own message, with the reply and the thread, no AI marker (ADR 0210). After the
     * send queue took it, the draft is redacted. On failure the draft stays.
     */
    fun send(context: Context, draft: Draft) {
        val session = session ?: return
        val draftsTimeline = timeline ?: return
        if (_state.value.sendingEventId != null) return
        _state.update { it.copy(sendingEventId = draft.eventId, failedEventId = null) }
        scope.launch {
            val sent = runCatching {
                val room = session.client.getRoom(draft.roomId) ?: throw IllegalStateException("no room")
                val target = draft.threadRoot?.let { root ->
                    room.timelineWithConfiguration(
                        TimelineConfiguration(
                            focus = TimelineFocus.Thread(root),
                            filter = TimelineFilter.All,
                            internalIdPrefix = "draft",
                            dateDividerMode = DateDividerMode.DAILY,
                            trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                            reportUtds = false,
                        ),
                    )
                } ?: room.timeline()
                val content = ComposerMarkdown.content(draft.body, emptyList())
                if (draft.replyTo != null) target.sendReply(content, draft.replyTo) else target.send(content)
            }.isSuccess
            if (!sent) {
                _state.update { it.copy(sendingEventId = null, failedEventId = draft.eventId) }
                return@launch
            }
            acknowledgeNothingSent(context)
            runCatching { draftsTimeline.redactEvent(EventOrTransactionId.EventId(draft.eventId), null) }
            _state.update { it.copy(sendingEventId = null) }
        }
    }

    /** Action discard, and the last step of edit: Timeline.redactEvent on the draft. It goes on every device. */
    suspend fun discard(context: Context, draft: Draft) {
        val timeline = timeline ?: return
        if (_state.value.sendingEventId == draft.eventId) return
        acknowledgeNothingSent(context)
        runCatching { timeline.redactEvent(EventOrTransactionId.EventId(draft.eventId), null) }
    }

    /** Action edit for a draft in a thread: the text becomes the thread's composer draft, which the thread view loads. */
    suspend fun keepAsThreadDraft(draft: Draft) {
        val root = draft.threadRoot ?: return
        val room = runCatching { session?.client?.getRoom(draft.roomId) }.getOrNull() ?: return
        room.keepDraft(draft.body, root)
    }

    private fun acknowledgeNothingSent(context: Context) {
        if (!_state.value.showsNothingSent) return
        _state.update { it.copy(showsNothingSent = false) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTHING_SENT, true).apply()
    }
}

/** Spec drafts: the banner above the composer for the draft of this chat. [edit] puts the draft in the composer. */
@Composable
fun AssistantDraftBanner(session: AccountSession, roomId: String, edit: suspend (AssistantDraftsStore.Draft) -> Unit) {
    val colors = BipeTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AssistantDraftsStore.state.collectAsState()
    val draft = state.banner(roomId, session.userId) ?: return
    val busy = state.sendingEventId != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.surfaceRaised, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .testTag(BipeScreen.DRAFTS.id),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(PhosphorIcon.PENCIL_SIMPLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.bipe_app_drafts_banner, draft.client),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentInk,
                )
                Text(draft.body, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { AssistantDraftsStore.send(context, draft) }, enabled = !busy) {
                Text(stringResource(R.string.bipe_app_drafts_send), fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = {
                scope.launch {
                    edit(draft)
                    AssistantDraftsStore.discard(context, draft)
                }
            }, enabled = !busy) {
                Text(stringResource(R.string.bipe_app_drafts_edit))
            }
            TextButton(onClick = { scope.launch { AssistantDraftsStore.discard(context, draft) } }, enabled = !busy) {
                Text(stringResource(R.string.bipe_app_drafts_discard), color = colors.inkPrimary)
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        val note = when {
            state.failedEventId == draft.eventId -> stringResource(R.string.bipe_app_drafts_send_failed)
            state.showsNothingSent -> stringResource(R.string.bipe_app_drafts_nothing_sent)
            else -> null
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.failedEventId == draft.eventId) colors.inkPrimary else colors.inkSecondary,
                modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
            )
        }
    }
}

/** Spec drafts openList: every draft of the account. A row opens its chat with that draft in the banner. */
@Composable
fun AssistantDraftsList(userId: String, onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val state by AssistantDraftsStore.state.collectAsState()
    val drafts = if (state.userId == userId) state.drafts else emptyList()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceBase)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag(BipeScreen.DRAFTS.id),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bipe_app_drafts_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bipe_app_close)) }
            }
            if (drafts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.bipe_app_drafts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(drafts, key = { it.eventId }) { draft ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AssistantDraftsStore.open(draft)
                                    onDismiss()
                                    onOpenChat(draft.roomId)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(draft.roomName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                            Text(draft.body, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.bipe_app_drafts_banner, draft.client), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                    }
                }
            }
        }
    }
}
