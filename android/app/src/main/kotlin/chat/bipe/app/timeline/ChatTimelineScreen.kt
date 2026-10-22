// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.InviterGreetings
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.notifications.NotificationsPrompt
import chat.bipe.shared.bipeHaptic
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.groups.DmDeleteSheet
import chat.bipe.app.polls.PollCreateScreen
import chat.bipe.app.polls.PollDetailScreen
import chat.bipe.phosphor.PhosphorIcon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.app.settings.SettingsKeys

/** Spec chat-timeline, with the rows of timeline-rows and the interactions of the message specs. */
@Composable
fun ChatTimelineScreen(
    session: AccountSession,
    media: MediaLoader,
    roomId: String,
    onBack: () -> Unit,
    onOpenGroupDetails: (() -> Unit)? = null,
    onHistoryHelp: () -> Unit = {},
    onDmDeleted: () -> Unit = onBack,
    /** Opens the chat's info: person-profile for a DM. */
    onOpenInfo: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val strings = RowStrings(
        you = stringResource(R.string.bipe_app_you),
        deleted = stringResource(R.string.bipe_app_message_deleted),
        deletedOwn = stringResource(R.string.bipe_app_message_deleted_own),
        undecryptable = stringResource(R.string.bipe_app_timeline_undecryptable),
    )
    val model = remember(roomId) {
        ChatTimelineModel(
            session, roomId, scope, InviterGreetings(context, session.userId), strings,
            sharesTyping = { sharesTyping(context) },
            handleChanges = HandleChangeStores.store(context, session),
        )
    }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val colors = BipeTheme.colors
    val roomName by model.roomName.collectAsState()
    val allRows by model.rows.collectAsState()
    val markers by ClearedChatsStore.markers.collectAsState()
    // Spec dm-delete: events at or below the marker of a cleared DM are gone.
    val rows = remember(allRows, markers) {
        val marker = markers.marker(roomId)
        if (marker == null) allRows else allRows.filter { row -> row.message?.let { it.sentAt > marker } ?: true }
    }
    val isLoadingHistory by model.isLoadingHistory.collectAsState()
    val reachedStart by model.reachedStart.collectAsState()
    val isDm by model.isDm.collectAsState()
    val draft by model.draft.collectAsState()
    val ui by model.ui.collectAsState()
    val inviterHint by model.inviterHint.collectAsState()
    val sentMessages by model.messages.collectAsState()
    NotificationsPrompt(session, roomId, sentMessages)
    val greeting = stringResource(R.string.bipe_inviter_greeting)
    val listState = rememberLazyListState()
    // group-details is for groups only: RoomInfo.isDm is the one DM test (ADR 0141).
    val isGroup by produceState(false, roomId) {
        value = runCatching { session.client.getRoom(roomId)?.roomInfo() }.getOrNull()?.let { !it.isDm && !it.isSpace } ?: false
    }
    var dmMenu by remember { mutableStateOf(false) }
    var deletingDm by rememberSaveable { mutableStateOf(false) }
    if (deletingDm) {
        DmDeleteSheet(session, roomId, onDismiss = { deletingDm = false }, onDeleted = {
            deletingDm = false
            onDmDeleted()
        })
    }
    var creatingPoll by rememberSaveable { mutableStateOf(false) }
    var openPollId by rememberSaveable { mutableStateOf<String?>(null) }
    if (creatingPoll) {
        PollCreateScreen(model) { creatingPoll = false }
        return
    }
    openPollId?.let { eventId ->
        PollDetailScreen(session, model, eventId) { openPollId = null }
        return
    }

    // The list is reversed: index 0 is the newest row, so the view opens at the bottom.
    val newestFirst = remember(rows) { rows.asReversed() }
    LaunchedEffect(rows.lastOrNull()?.id) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // Action markAsRead: while the newest row is on screen.
    val atNewest by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atNewest, rows.lastOrNull()?.id) {
        if (atNewest && rows.isNotEmpty()) model.markAsRead()
    }
    LaunchedEffect(ui.scrollTarget) {
        val target = ui.scrollTarget ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index)
        model.scrolledToTarget()
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_TIMELINE.id), color = colors.surfaceBase) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = null, tint = colors.glyphPrimary)
                    }
                    // The header opens the chat's info: person-profile for a DM.
                    Row(
                        modifier = Modifier.weight(1f).then(if (onOpenInfo != null) Modifier.clickable(onClick = onOpenInfo) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(roomName, null, media, size = 32.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            roomName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isGroup && onOpenGroupDetails != null) {
                        IconButton(onClick = onOpenGroupDetails) {
                            Icon(
                                painterResource(PhosphorIcon.INFO.drawable),
                                contentDescription = stringResource(R.string.bipe_app_group_details_title),
                                tint = colors.glyphPrimary,
                            )
                        }
                    }
                    if (isDm) {
                        Box {
                            IconButton(onClick = { dmMenu = true }) {
                                Icon(
                                    painterResource(PhosphorIcon.INFO.drawable),
                                    contentDescription = stringResource(R.string.bipe_dm_details_title),
                                    tint = colors.glyphPrimary,
                                )
                            }
                            DropdownMenu(expanded = dmMenu, onDismissRequest = { dmMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bipe_dm_delete_action), color = colors.trustDangerInk) },
                                    onClick = {
                                        dmMenu = false
                                        deletingDm = true
                                    },
                                )
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(newestFirst, key = { it.id }) { row ->
                        TimelineRowItem(
                            row = row,
                            isDm = isDm,
                            isHighlighted = ui.highlightedRowId == row.id,
                            model = model,
                            media = media,
                            onLongPress = { if (model.openActions(row.id)) view.bipeHaptic(BipeHaptic.Hold) },
                            onHistoryHelp = onHistoryHelp,
                            onOpenPoll = { openPollId = it },
                        )
                    }
                    item(key = "history") {
                        // Composed while the top of the list is on screen, again after each page (action loadHistory).
                        LaunchedEffect(rows.size) { model.loadHistory() }
                        if (isLoadingHistory && !reachedStart) {
                            Text(
                                stringResource(R.string.bipe_app_timeline_loading_history),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                }
                inviterHint?.let { hint -> InviterHintRow(hint, roomName) { model.sendGreeting(greeting) } }
                TypingLine(ui.typingNames)
                ComposerBar(
                    draft = draft,
                    canSend = model.canSend,
                    ui = ui,
                    onEdit = model::editDraft,
                    onSend = {
                        if (ui.editing == null) view.bipeHaptic(BipeHaptic.Tap)
                        model.send()
                    },
                    onCancelReply = model::cancelReply,
                    onCancelEdit = model::cancelEdit,
                    onCreatePoll = { creatingPoll = true },
                )
            }
            ui.notice?.let { notice ->
                Text(
                    noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 110.dp)
                        .background(colors.surfaceRaised, CircleShape)
                        .border(1.dp, colors.lineSubtle, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    model.row(ui.actionsRowId)?.let { row ->
        MessageActionsSheet(
            model = model,
            row = row,
            onCopy = { text -> copyToClipboard(context, text) },
            onHaptic = { view.bipeHaptic(it) },
        )
    }
    if (ui.pickerOpenFor != null) {
        EmojiPickerSheet(
            onPick = { key ->
                view.bipeHaptic(BipeHaptic.Tick)
                model.pickEmoji(key)
            },
            onDismiss = model::closePicker,
        )
    }
    ui.reactionSheet?.let { ReactionSendersSheet(it, onDismiss = model::closeSenders) }
    ui.readersSheet?.let { ReadersSheet(it, media, onDismiss = model::closeReaders) }
    ui.report?.let { report ->
        MessageReportSheet(
            report = report,
            onReason = model::editReportReason,
            onAlsoBlock = model::setReportAlsoBlock,
            onSend = model::sendReport,
            onCancel = model::cancelReport,
        )
    }
    if (ui.confirmDeleteRowId != null) {
        AlertDialog(
            onDismissRequest = model::cancelDelete,
            title = { Text(stringResource(R.string.bipe_app_message_delete_title)) },
            text = { Text(stringResource(R.string.bipe_app_message_delete_body)) },
            confirmButton = {
                TextButton(onClick = model::confirmDelete) {
                    Text(stringResource(R.string.bipe_app_message_action_delete), color = colors.trustDangerInk)
                }
            },
            dismissButton = {
                TextButton(onClick = model::cancelDelete) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.inkPrimary,
            textContentColor = colors.inkSecondary,
        )
    }
}

/** Spec typing-indicator sharesTyping: the presence setting (bipe_privacy_presence). On unless turned off. */
private fun sharesTyping(context: Context): Boolean =
    context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getBoolean(SettingsKeys.SHARE_PRESENCE, true)

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun noticeText(notice: TimelineNotice): String = when (notice) {
    TimelineNotice.COPIED -> stringResource(R.string.bipe_app_copied)
    TimelineNotice.REACTION_FAILED -> stringResource(R.string.bipe_app_reaction_failed)
    TimelineNotice.EDIT_FAILED -> stringResource(R.string.bipe_app_message_edit_failed)
    TimelineNotice.DELETE_FAILED -> stringResource(R.string.bipe_app_message_delete_failed)
    TimelineNotice.REPORTED -> stringResource(R.string.bipe_app_message_reported)
}

/** Spec typing-indicator: who is typing, above the composer. */
@Composable
private fun TypingLine(names: List<String>) {
    val colors = BipeTheme.colors
    val text = when (names.size) {
        0 -> return
        1 -> stringResource(R.string.bipe_app_typing_one, names[0])
        2 -> stringResource(R.string.bipe_app_typing_two, names[0], names[1])
        else -> stringResource(R.string.bipe_app_typing_many, names[0])
    }
    val view = LocalView.current
    // Screen readers announce the line once when it appears, not on every change.
    LaunchedEffect(Unit) { view.announceForAccessibility(text) }
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 200), RepeatMode.Reverse),
                    label = "dot$index",
                )
                Box(Modifier.size(5.dp).alpha(alpha).background(colors.glyphSecondary, CircleShape))
            }
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The composer with the reply bar (message-reply) and the edit bar (message-edit). */
@Composable
private fun ComposerBar(
    draft: String,
    canSend: Boolean,
    ui: TimelineInteractions,
    onEdit: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onCreatePoll: () -> Unit,
) {
    val colors = BipeTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.replyingToRowId, ui.editing) {
        if (ui.replyingToRowId != null || ui.editing != null) runCatching { focus.requestFocus() }
    }
    Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised)) {
        val reply = ui.replyingTo
        val editing = ui.editing
        if (reply != null) {
            ComposerContextBar(
                icon = PhosphorIcon.ARROW_BEND_UP_LEFT,
                title = stringResource(R.string.bipe_app_message_replying_to, reply.senderName.orEmpty()),
                preview = reply.preview,
                onClose = onCancelReply,
            )
        } else if (editing != null) {
            ComposerContextBar(
                icon = PhosphorIcon.PENCIL_SIMPLE,
                title = stringResource(R.string.bipe_app_message_editing),
                preview = editing.originalText,
                onClose = onCancelEdit,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Opens poll-create (the attach menu comes with the composer spec, bipe-chat/bipe#563).
            if (editing == null) {
                IconButton(onClick = onCreatePoll) {
                    Icon(
                        painterResource(PhosphorIcon.CHART_BAR.drawable),
                        contentDescription = stringResource(R.string.bipe_app_poll_create_title),
                        tint = colors.glyphPrimary,
                    )
                }
            }
            val placeholder = stringResource(R.string.bipe_app_timeline_composer_placeholder)
            BasicTextField(
                value = draft,
                onValueChange = onEdit,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .background(colors.surfaceSunken, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.background(if (canSend) colors.accentFill else colors.surfaceSunken, CircleShape),
            ) {
                Icon(
                    painterResource(if (editing == null) PhosphorIcon.PAPER_PLANE_RIGHT.drawable else PhosphorIcon.CHECK.drawable),
                    contentDescription = stringResource(if (editing == null) R.string.bipe_app_timeline_send else R.string.bipe_app_message_save_edit),
                    tint = if (canSend) colors.inkOnAccent else colors.inkDisabled,
                )
            }
        }
    }
}

@Composable
private fun ComposerContextBar(icon: PhosphorIcon, title: String, preview: String?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
            if (preview != null) {
                Text(preview, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                painterResource(PhosphorIcon.X.drawable),
                contentDescription = stringResource(R.string.bipe_app_cancel),
                tint = colors.glyphSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun InviterHintRow(hint: InviterGreetings.Greeting, inviterName: String, greet: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.accentWash).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_inviter_knows_you_joined, inviterName), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint.handle?.let { stringResource(R.string.bipe_inviter_hint, it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.bipe_inviter_hint_action),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.background(colors.accentFill, CircleShape).clickable(onClick = greet).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
