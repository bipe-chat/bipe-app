// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeChatAssignment
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch

// Specs work-chats, work-chat-bar and work-notes (bipe ADR 0284 phase A, bipe-chat/bipe#1293). Everything here shows
// only while WorkAccount.State.active: a work account with two or more people. With one person nothing changes.

/** State filter of spec work-chats. */
enum class WorkChatFilter { MINE, POOL, ALL }

/** The queue state of an account, or null when it has no queue. */
@Composable
fun workQueueState(userId: String): WorkAccount.State? {
    val account = WorkQueue.account(userId) ?: return null
    val state by account.state.collectAsState()
    return state.takeIf { it.active }
}

/** Spec work-chats row line: Com você, Com <name>, or nothing for the pool. */
@Composable
fun workAssigneeText(state: WorkAccount.State, roomId: String): String? {
    val assignee = state.assignee(roomId) ?: return null
    return if (assignee == state.mySeat) stringResource(R.string.bipe_chat_assignee_me)
    else stringResource(R.string.bipe_chat_assignee_other, state.seatName(assignee).orEmpty())
}

/** Spec work-chats selectFilter: on the device, no call. */
fun WorkAccount.State.shows(filter: WorkChatFilter, roomId: String): Boolean = when (filter) {
    WorkChatFilter.ALL -> true
    WorkChatFilter.MINE -> assignee(roomId).let { it != null && it == mySeat }
    WorkChatFilter.POOL -> assignee(roomId) == null
}

/** Spec work-chats: the three filters above the list. */
@Composable
fun WorkChatFilters(selected: WorkChatFilter, onSelect: (WorkChatFilter) -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(BipeScreen.WORK_CHATS.id),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            WorkChatFilter.MINE to R.string.bipe_chats_filter_mine,
            WorkChatFilter.POOL to R.string.bipe_chats_filter_pool,
            WorkChatFilter.ALL to R.string.bipe_chats_filter_all,
        ).forEach { (filter, label) ->
            val on = filter == selected
            TextButton(
                onClick = { onSelect(filter) },
                modifier = if (on) Modifier.background(colors.surfaceRaised, RoundedCornerShape(16.dp)) else Modifier,
            ) {
                // The selected filter is bold as well as raised: a state is never shown by colour alone.
                Text(
                    stringResource(label),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.accentInk else colors.inkSecondary,
                )
            }
        }
    }
}

private fun statusLabel(status: String): Int = when (status) {
    BipeChatAssignment.WAITING -> R.string.bipe_chat_status_waiting
    BipeChatAssignment.SOLVED -> R.string.bipe_chat_status_solved
    else -> R.string.bipe_chat_status_open
}

/**
 * Spec work-chat-bar: who has the customer chat and its status, Pegar, Notas and a menu with Passar para, Devolver and
 * the status. The banner shows when another person has the chat.
 */
@Composable
fun WorkChatBar(session: AccountSession, roomId: String) {
    val colors = BipeTheme.colors
    val account = WorkQueue.account(session.userId) ?: return
    val state by account.state.collectAsState()
    if (!state.active) return
    var menu by remember { mutableStateOf(false) }
    var giving by remember { mutableStateOf(false) }
    var notes by rememberSaveable(roomId) { mutableStateOf(false) }
    val assignee = state.assignee(roomId)
    val mine = assignee != null && assignee == state.mySeat
    val phase = state.phase(roomId)
    val busy = phase == WorkAccount.Phase.UPDATING
    val line = when {
        assignee == null -> stringResource(R.string.bipe_chat_assignee_none)
        mine -> stringResource(R.string.bipe_chat_assignee_me)
        else -> stringResource(R.string.bipe_chat_assignee_other, state.seatName(assignee).orEmpty())
    }
    Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).testTag(BipeScreen.WORK_CHAT_BAR.id)) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
                Text(stringResource(statusLabel(state.status(roomId))), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            if (!mine) TextButton(onClick = { account.take(roomId) }, enabled = !busy) { Text(stringResource(R.string.bipe_chat_take)) }
            TextButton(onClick = { notes = true }) { Text(stringResource(R.string.bipe_chat_notes)) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Icon(
                        painterResource(PhosphorIcon.DOTS_THREE.drawable),
                        contentDescription = stringResource(R.string.bipe_chat_actions),
                        tint = colors.glyphPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.bipe_chat_give)) }, onClick = {
                        menu = false
                        giving = true
                    })
                    if (assignee != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bipe_chat_release)) }, onClick = {
                            menu = false
                            account.release(roomId)
                        })
                    }
                    BipeChatAssignment.STATUSES.filter { it != state.status(roomId) }.forEach { status ->
                        DropdownMenuItem(text = { Text(stringResource(statusLabel(status))) }, onClick = {
                            menu = false
                            account.setStatus(roomId, status)
                        })
                    }
                }
            }
        }
        if (assignee != null && !mine) {
            Text(
                stringResource(R.string.bipe_chat_taken_banner, state.seatName(assignee).orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.trustCautionInk,
                modifier = Modifier.fillMaxWidth().background(colors.trustCautionWash).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (phase == WorkAccount.Phase.FAILED) {
            Text(
                stringResource(R.string.bipe_chat_assign_error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.trustDangerInk,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
    if (giving) {
        val others = state.seats?.activeSeats.orEmpty().filter { it.id != assignee }
        AlertDialog(
            onDismissRequest = { giving = false },
            title = { Text(stringResource(R.string.bipe_chat_give_title)) },
            text = {
                Column {
                    others.forEach { seat ->
                        TextButton(onClick = {
                            giving = false
                            account.give(roomId, seat.id)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (seat.id == state.mySeat) stringResource(R.string.bipe_team_you) else seat.name,
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.inkPrimary,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { giving = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (notes) WorkNotesSheet(session, account, roomId, onDismiss = { notes = false })
}

/**
 * Spec work-chat-bar reply: in a chat another person has, the composer shows bipe_chat_taken_reply first. The record
 * is a help, not a lock: the tap shows the composer. With no queue, or a null [roomId] (not a customer chat), the
 * composer shows as before.
 */
@Composable
fun WorkComposerGate(userId: String, roomId: String?, composer: @Composable () -> Unit) {
    val state = workQueueState(userId)
    var replyAnyway by rememberSaveable(roomId) { mutableStateOf(false) }
    val assignee = roomId?.let { state?.assignee(it) }
    if (state == null || assignee == null || assignee == state.mySeat || replyAnyway) {
        composer()
        return
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = { replyAnyway = true }) {
            Text(stringResource(R.string.bipe_chat_taken_reply), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Spec work-chat-bar notes: bipe_chat_reply_by under an own reply that has a valid reply-by event. */
@Composable
fun WorkReplyByLine(userId: String, eventId: String?) {
    val name = workQueueState(userId)?.replyByName(eventId) ?: return
    Text(stringResource(R.string.bipe_chat_reply_by, name), style = MaterialTheme.typography.labelSmall, color = BipeTheme.colors.inkSecondary)
}

/** Spec work-notes: the notes of one customer chat, oldest first, and a field for a new one. */
@Composable
private fun WorkNotesSheet(session: AccountSession, account: WorkAccount, roomId: String, onDismiss: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by account.state.collectAsState()
    val notes = state.notes(roomId)
    var draft by rememberSaveable(roomId) { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    LaunchedEffect(session) { accountName = runCatching { session.client.displayName() }.getOrNull().orEmpty() }
    val sending = state.notesPhase == WorkAccount.NotesPhase.SENDING
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceBase)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .testTag(BipeScreen.WORK_NOTES.id),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bipe_chat_notes_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bipe_app_close)) }
            }
            Text(
                stringResource(R.string.bipe_chat_notes_hint, accountName),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.notesPhase == WorkAccount.NotesPhase.LOADING ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    notes.isEmpty() -> Text(
                        stringResource(R.string.bipe_chat_notes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(notes, key = { it.eventId }) { note ->
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // State notes: the sender seat when known, from the seat list.
                                state.seatName(note.seatId)?.let { author ->
                                    Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.accentInk)
                                }
                                Text(note.body, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                                Text(
                                    android.text.format.DateUtils.formatDateTime(context, note.timestampMs,
                                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }
            if (state.notesPhase == WorkAccount.NotesPhase.FAILED) {
                Text(
                    stringResource(R.string.bipe_chat_notes_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.trustDangerInk,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(BipeRegistries.WORK_NOTE_BODY_MAX_LENGTH) },
                    placeholder = { Text(stringResource(R.string.bipe_chat_notes_placeholder)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                )
                val canSend = draft.isNotBlank() && !sending
                IconButton(onClick = {
                    val body = draft
                    scope.launch { if (account.sendNote(roomId, body)) draft = "" }
                }, enabled = canSend) {
                    Icon(
                        painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable),
                        contentDescription = stringResource(R.string.bipe_app_timeline_send),
                        tint = if (canSend) colors.accentInk else colors.inkDisabled,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
