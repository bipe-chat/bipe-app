// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeMinTapTarget
import chat.bipe.app.common.LocalBipeCompanySeals
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.OfficialBadge
import chat.bipe.app.common.SealMark
import chat.bipe.app.common.bipeHaptic
import chat.bipe.app.common.sealMarkOf
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.company.BipeSealMark
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeHomeChat
import chat.bipe.shared.generated.BipeHomeChatKind
import chat.bipe.shared.generated.BipeHomeReason
import chat.bipe.shared.generated.BipeHomeScore
import chat.bipe.shared.generated.BipeHomeTile
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec chat-list, Mural setting (ADR 0246): the accounts with Mural on, on this device. Default off. It does not sync and
 * it is not in account data. The app has no system backup (android:allowBackup false).
 */
class MuralSetting private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val accounts: StateFlow<Set<String>> = _accounts.asStateFlow()

    fun set(userId: String, on: Boolean) {
        _accounts.value = if (on) _accounts.value + userId else _accounts.value - userId
        prefs.edit().putStringSet(KEY, _accounts.value).apply()
    }

    companion object {
        private const val FILE = "bipe_mural"
        private const val KEY = "accounts"
        private var shared: MuralSetting? = null

        /** One store per process: the settings row and the tabs see the same value at once. */
        @Synchronized
        fun of(context: Context): MuralSetting = shared ?: MuralSetting(context.applicationContext).also { shared = it }
    }
}

/** Spec chat-list, Mural inputs: the inputs of BipeHomeScore for one chat. Step 1 of ADR 0246 passes no model field. */
fun ChatSummary.homeChat(nowMs: Long, isCompany: Boolean, hasSeal: Boolean): BipeHomeChat = BipeHomeChat(
    id = roomId,
    kind = when {
        !isDm -> BipeHomeChatKind.GROUP
        isCompany -> BipeHomeChatKind.COMPANY
        else -> BipeHomeChatKind.PERSON
    },
    hasSeal = hasSeal,
    official = isOfficial,
    favourite = isFavourite,
    muted = isMuted,
    mentioned = mentionCount > 0,
    unreadCount = unreadCount,
    lastFromOther = lastFromOther,
    minutesSinceActivity = lastActivity?.let { ((nowMs - it) / MILLIS_PER_MINUTE).toInt() },
    messagesPerWeek = messagesPerWeek,
)

/**
 * The tiles in plan order. A plan change waits until [atRest] (spec chat-list, Mural grid rules); new text and badges come
 * from the chats, so they show in place. The plan runs again when a chat changes, not on a timer.
 */
@Composable
internal fun rememberMuralPlan(chats: List<ChatSummary>, media: MediaLoader, atRest: Boolean): List<BipeHomeTile> {
    val seals = LocalBipeCompanySeals.current
    val sealStates = seals?.states?.collectAsState()?.value
    val personSeals = seals?.personSeals?.collectAsState()?.value
    // Spec chat-list, Mural inputs: GET /v1/accounts/{userId}/type through the avatar's cache. Unknown is a person.
    val companies = remember { mutableStateMapOf<String, Boolean>() }
    val peers = remember(chats) { chats.filter { it.isDm }.mapNotNull { it.peerUserId }.distinct() }
    LaunchedEffect(peers) {
        for (peer in peers) if (peer !in companies) companies[peer] = media.isCompany(peer)
    }
    val knownCompanies = companies.toMap()
    val live = remember(chats, knownCompanies, sealStates, personSeals) {
        val now = System.currentTimeMillis()
        BipeHomeScore.plan(
            chats.map { chat ->
                val peer = chat.peerUserId.takeIf { chat.isDm }
                chat.homeChat(
                    now,
                    isCompany = peer != null && knownCompanies[peer] == true,
                    hasSeal = peer != null && seals?.mark(peer) == BipeSealMark.COMPANY,
                )
            },
        )
    }
    var shown by remember { mutableStateOf(live) }
    LaunchedEffect(live, atRest) { if (atRest) shown = live }
    return shown
}

/** Sets [touching] while a finger is on the list. It consumes nothing: the tiles and the scroll get every event. */
internal fun Modifier.muralTouchWatch(touching: MutableState<Boolean>): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        touching.value = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            touching.value = false
        }
    }
}

/** One tile of the grid: the chat of this moment, and the tier and reason of the plan on screen. */
private class MuralEntry(val chat: ChatSummary, val tier: Int, val reason: BipeHomeReason?)

/** Spec chat-list, Mural tiles: the tile grid, then bipe_chat_mural_view_list. A tile has its own item, a row of tiles too. */
internal fun LazyListScope.muralItems(
    plan: List<BipeHomeTile>,
    chats: List<ChatSummary>,
    media: MediaLoader,
    model: ChatListModel,
    commands: (ChatSummary) -> ChatRowCommands,
    onOpen: (String) -> Unit,
    onReplyFocus: (Boolean) -> Unit,
    onShowList: () -> Unit,
    onLoadMore: () -> Unit,
) {
    if (chats.isEmpty()) return
    val byId = chats.associateBy { it.roomId }
    val entries = plan.mapNotNull { tile ->
        val chat = byId[tile.id]
        val tier = tile.tier
        if (chat == null || tier == null) null else MuralEntry(chat, tier, tile.reason)
    }
    val ownerId = model.session.userId
    entries.filter { it.tier == 1 }.forEachIndexed { index, entry ->
        item(key = "mural-${entry.chat.roomId}") {
            WideTile(entry, isFirst = index == 0, media, ownerId, commands(entry.chat), { onOpen(entry.chat.roomId) }, model, onReplyFocus)
        }
    }
    for (tier in listOf(2, 3)) {
        val columns = BipeHomeScore.tiers.first { it.tier == tier }.columns ?: 1
        entries.filter { it.tier == tier }.chunked(columns).forEach { row ->
            item(key = "mural-${row.first().chat.roomId}") {
                // Every tile has weight 1 and no spacer fills the row: the last row of a tier stretches, so the grid has no hole.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = TILE_MARGIN.dp, vertical = TILE_GAP.dp / 2),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP.dp),
                ) {
                    row.forEach { entry ->
                        val tile = Modifier.weight(1f).fillMaxHeight()
                        if (tier == 2) MediumTile(entry, media, ownerId, commands(entry.chat), { onOpen(entry.chat.roomId) }, tile)
                        else SmallTile(entry, media, ownerId, commands(entry.chat), { onOpen(entry.chat.roomId) }, tile)
                    }
                }
            }
        }
    }
    val quiet = entries.filter { it.tier == 4 }
    if (quiet.isNotEmpty()) item(key = "mural-quiet") {
        QuietTiles(quiet, media, ownerId, commands, onOpen)
    }
    item(key = "mural-view-list") {
        ListEntryRow(PhosphorIcon.CARET_RIGHT, stringResource(R.string.bipe_chat_mural_view_list), onClick = onShowList)
        // Spec chat-list loadMore: the end of the screen asks for the next page, and again while a page adds rooms.
        LaunchedEffect(chats.size) { onLoadMore() }
    }
}

/** Tier 1: full width. The first tile has the last messages, the draft and the reply field; the second has the last message. */
@Composable
private fun WideTile(
    entry: MuralEntry, isFirst: Boolean, media: MediaLoader, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit,
    model: ChatListModel, onReplyFocus: (Boolean) -> Unit,
) {
    val chat = entry.chat
    val messages = chat.tileMessages(if (isFirst) BipeHomeScore.FIRST_TILE_MESSAGES else BipeHomeScore.SECOND_TILE_MESSAGES)
    TileFrame(
        entry, ownerId, commands, onOpen, messages,
        color = BipeTheme.colors.accentWash,
        modifier = Modifier.fillMaxWidth().padding(horizontal = TILE_MARGIN.dp, vertical = TILE_GAP.dp / 2),
        footer = { if (isFirst) TileReplyField(model, chat.roomId, onReplyFocus) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(chat.name, chat.avatarUrl, media, size = WIDE_AVATAR.dp, userId = chat.peerUserId, official = chat.isNewsRoom, members = chat.avatarMembers)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { TileName(chat, chat.name, MaterialTheme.typography.titleMedium) }
            ChatUnreadBadge(chat)
        }
        messages.forEach { TileBubble(it) }
    }
}

/** Tier 2: half of the width. Avatar, name, reason and one bubble. */
@Composable
private fun MediumTile(entry: MuralEntry, media: MediaLoader, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit, modifier: Modifier) {
    val colors = BipeTheme.colors
    val chat = entry.chat
    val messages = chat.tileMessages(BipeHomeScore.TIER2_BUBBLES)
    TileFrame(entry, ownerId, commands, onOpen, messages, color = colors.surfaceRaised, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(chat.name, chat.avatarUrl, media, size = TILE_AVATAR.dp, userId = chat.peerUserId, official = chat.isNewsRoom, members = chat.avatarMembers)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                TileName(chat, chat.name, MaterialTheme.typography.titleSmall)
                entry.reason?.let { reason ->
                    Text(
                        stringResource(reasonText(reason)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accentInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ChatUnreadBadge(chat)
        }
        messages.forEach { TileBubble(it) }
    }
}

/** Tier 3: one third of the width. Avatar, first word of the name, two lines of text. */
@Composable
private fun SmallTile(entry: MuralEntry, media: MediaLoader, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit, modifier: Modifier) {
    val colors = BipeTheme.colors
    val chat = entry.chat
    val messages = chat.tileMessages(1)
    TileFrame(
        entry, ownerId, commands, onOpen, messages,
        color = colors.surfaceRaised, modifier = modifier, horizontal = Alignment.CenterHorizontally,
    ) {
        Avatar(chat.name, chat.avatarUrl, media, size = TILE_AVATAR.dp, userId = chat.peerUserId, official = chat.isNewsRoom, members = chat.avatarMembers)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) {
                TileName(chat, chat.name.trim().substringBefore(' '), MaterialTheme.typography.labelLarge)
            }
            ChatUnreadBadge(chat)
        }
        messages.forEach { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = BipeHomeScore.TIER3_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Tap opens the chat and long press opens the chat-row-actions menu. [footer] is outside the tap area: a tap on the reply
 * field does not open the chat. A screen reader reads the label of the tile, not its parts.
 */
@Composable
private fun TileFrame(
    entry: MuralEntry, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit, messages: List<String>,
    color: Color, modifier: Modifier, horizontal: Alignment.Horizontal = Alignment.Start,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val chat = entry.chat
    val menu = rememberChatRowMenu(ownerId, chat.roomId)
    val view = LocalView.current
    val label = tileLabel(entry, messages)
    Box(modifier.clip(RoundedCornerShape(TILE_RADIUS.dp)).background(color)) {
        Column(Modifier.fillMaxHeight()) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight()
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { view.bipeHaptic(BipeHaptic.Hold); menu.value = true },
                        onLongClickLabel = stringResource(R.string.bipe_chat_actions),
                    )
                    .chatRowSemantics(chat, commands, menu)
                    .clearAndSetSemantics { contentDescription = label }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = horizontal,
                content = content,
            )
            footer()
        }
        ChatRowMenu(chat, commands, menu)
    }
}

/** Tier 4: avatars only, in rows that wrap, under bipe_chat_mural_no_news. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietTiles(
    entries: List<MuralEntry>, media: MediaLoader, ownerId: String, commands: (ChatSummary) -> ChatRowCommands, onOpen: (String) -> Unit,
) {
    val colors = BipeTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = TILE_MARGIN.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bipe_chat_mural_no_news), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TILE_GAP.dp), verticalArrangement = Arrangement.spacedBy(TILE_GAP.dp)) {
            entries.forEach { entry -> QuietTile(entry, media, ownerId, commands(entry.chat)) { onOpen(entry.chat.roomId) } }
        }
    }
}

@Composable
private fun QuietTile(entry: MuralEntry, media: MediaLoader, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    val chat = entry.chat
    val menu = rememberChatRowMenu(ownerId, chat.roomId)
    val view = LocalView.current
    val label = tileLabel(entry, emptyList())
    Box {
        Box(
            Modifier.size(BipeMinTapTarget)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { view.bipeHaptic(BipeHaptic.Hold); menu.value = true },
                    onLongClickLabel = stringResource(R.string.bipe_chat_actions),
                )
                .chatRowSemantics(chat, commands, menu),
        ) {
            Box(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Avatar(chat.name, chat.avatarUrl, media, size = TILE_AVATAR.dp, userId = chat.peerUserId, official = chat.isNewsRoom, members = chat.avatarMembers)
                if (chat.isUnread) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(14.dp)
                            .border(2.dp, colors.surfaceBase, CircleShape)
                            .padding(2.dp)
                            .background(colors.accentFill, CircleShape),
                    )
                }
            }
        }
        ChatRowMenu(chat, commands, menu)
    }
}

/** The name, with the official badge or the seal after it (spec chat-list). [name] is the whole name or its first word. */
@Composable
private fun TileName(chat: ChatSummary, name: String, style: TextStyle) {
    val sealUserId = chat.peerUserId.takeIf { chat.isDm }
    val hasSeal = sealMarkOf(sealUserId) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = style,
            color = BipeTheme.colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (chat.isOfficial) {
            Spacer(Modifier.width(4.dp))
            OfficialBadge()
        }
        if (hasSeal) {
            Spacer(Modifier.width(4.dp))
            SealMark(sealUserId)
        }
    }
}

@Composable
private fun TileBubble(text: String) {
    val colors = BipeTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkPrimary,
        maxLines = BUBBLE_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(colors.surfaceBase, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * The reply field of the first tile. It starts with the saved draft of the room, and keeps the text as that draft like
 * the composer does: 1 s after the last change, and when the field goes away.
 */
@Composable
private fun TileReplyField(model: ChatListModel, roomId: String, onFocus: (Boolean) -> Unit) {
    val colors = BipeTheme.colors
    var text by remember(roomId) { mutableStateOf("") }
    var edited by remember(roomId) { mutableStateOf(false) }
    var savedDraft by remember(roomId) { mutableStateOf<String?>(null) }
    var failed by remember(roomId) { mutableStateOf(false) }
    LaunchedEffect(roomId) {
        val draft = model.tileDraft(roomId)
        if (draft != null && !edited) {
            text = draft
            savedDraft = draft
        }
    }
    LaunchedEffect(roomId, text) {
        if (edited) {
            delay(DRAFT_SAVE_DELAY_MS)
            model.keepTileDraft(roomId, text)
        }
    }
    DisposableEffect(roomId) {
        onDispose {
            if (edited) model.keepTileDraft(roomId, text)
            // A field that leaves the list reports no focus change, and a held plan must not stay held.
            onFocus(false)
        }
    }
    fun send() {
        val message = text
        if (message.isBlank()) return
        text = ""
        edited = false
        failed = false
        savedDraft = null
        model.replyFromTile(roomId, message) { sent ->
            if (!sent) {
                text = message
                edited = true
                failed = true
            }
        }
    }
    val placeholder = stringResource(R.string.bipe_chats_message_placeholder)
    val canSend = text.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
        savedDraft?.takeIf { !edited }?.let { draft ->
            Text(
                stringResource(R.string.bipe_app_chat_list_draft, draft),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    edited = true
                    failed = false
                },
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = placeholder }
                    .background(colors.surfaceBase, RoundedCornerShape(24.dp))
                    // 22 dp line + 2 x 13 = 48 dp, as in the composer.
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                        inner()
                    }
                },
            )
            IconButton(onClick = { send() }, enabled = canSend) {
                Icon(
                    painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable),
                    contentDescription = stringResource(R.string.bipe_app_timeline_send),
                    tint = if (canSend) colors.accentFill else colors.inkDisabled,
                )
            }
        }
        // Spec chat-list replyFromTile: a failed send keeps the text and shows the composer's failed state.
        if (failed) {
            TextButton(onClick = { send() }) {
                Text(stringResource(R.string.bipe_app_timeline_failed), color = colors.trustDangerInk)
            }
        }
    }
}

/** Name, reason and unread count (spec chat-list, Mural grid rules), then the text the tile shows. */
@Composable
private fun tileLabel(entry: MuralEntry, messages: List<String>): String {
    val chat = entry.chat
    val reason = entry.reason?.let { stringResource(reasonText(it)) }
    val count = NumberFormat.getIntegerInstance().format(chat.unreadCount)
    val unread = when {
        chat.unreadCount > 0 -> stringResource(R.string.bipe_app_chat_list_unread, count)
        chat.isMarkedUnread -> stringResource(R.string.bipe_chat_unread_state)
        else -> null
    }
    return (listOfNotNull(chat.name, reason, unread) + messages).joinToString(", ")
}

private fun reasonText(reason: BipeHomeReason): Int = when (reason) {
    BipeHomeReason.MENTION -> R.string.bipe_chat_mural_reason_mention
    BipeHomeReason.UNANSWERED -> R.string.bipe_chat_mural_reason_unanswered
}

/** The newest [count] messages, oldest first. The row text is the newest one when the local read does not have it yet. */
private fun ChatSummary.tileMessages(count: Int): List<String> {
    val row = lastMessage
    val messages = if (row == null || row in recentMessages) recentMessages else recentMessages + row
    return messages.takeLast(count)
}

private const val MILLIS_PER_MINUTE = 60_000L
/** The composer's delay (spec composer leave). */
private const val DRAFT_SAVE_DELAY_MS = 1000L
private const val TILE_MARGIN = 12
private const val TILE_GAP = 8
private const val TILE_RADIUS = 22
private const val WIDE_AVATAR = 64
private const val TILE_AVATAR = 44
private const val BUBBLE_LINES = 3
