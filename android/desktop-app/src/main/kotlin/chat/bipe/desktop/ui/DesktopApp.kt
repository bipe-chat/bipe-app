// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.*
import chat.bipe.desktop.link.*
import chat.bipe.desktop.safety.MessageSafety
import chat.bipe.desktop.safety.registryId
import chat.bipe.desktop.storage.StoredSlot
import chat.bipe.desktop.ui.common.BipeButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

@Composable
fun DesktopApp(model: DesktopAppModel, phase: AppPhase, colors: DesktopColors) {
    val s = model.catalog
    Surface(Modifier.fillMaxSize(), color = colors["surface.base"]) {
        when (phase) {
            AppPhase.Loading -> Centered { Text(s.text("bipe_app_start_loading")) }
            is AppPhase.Welcome -> Centered {
                Text(s.text("bipe_web_signin_title"), style = MaterialTheme.typography.headlineMedium)
                phase.slots.forEachIndexed { index, slot ->
                    val label = when (slot) {
                        is StoredSlot.Linked -> "bipe_tab_you"
                        is StoredSlot.Unavailable -> "bipe_app_start_error"
                        else -> "bipe_desktop_pending_title"
                    }
                    BipeButton(s.text(label) + " ${index + 1}", { model.open(slot) })
                }
                BipeButton(s.text("bipe_linked_devices_link_computer"), { model.link() })
                Text(s.versionLabel, style = MaterialTheme.typography.bodySmall)
            }
            is AppPhase.Linking -> PhoneLinkView(model, phase, colors)
            is AppPhase.Recovering -> PendingRecoveryView(model, phase)
            is AppPhase.Ready -> Workspace(model, phase, colors)
            is AppPhase.Failed -> Centered {
                Text(s.text("bipe_app_start_error"))
                if (phase.needsPhone) Text(s.text("bipe_mac_linked_devices_footer"))
                BipeButton(s.text("bipe_app_retry"), { model.refresh() })
            }
        }
    }
}

@Composable private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable private fun PhoneLinkView(model: DesktopAppModel, linking: AppPhase.Linking, colors: DesktopColors) {
    val s = model.catalog
    val phase by linking.attempt.phase.collectAsState()
    var digits by remember(linking.attempt) { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Centered {
        Text(s.text("bipe_web_signin_title"), style = MaterialTheme.typography.headlineMedium)
        when (val state = phase) {
            PhoneLinkPhase.Idle, PhoneLinkPhase.Starting -> Text(s.text("bipe_app_link_phone_preparing"))
            is PhoneLinkPhase.Qr -> {
                val modules = remember(state.payload) { BinaryLinkQr.encode(state.payload) }
                val description = s.text("bipe_web_signin_step_scan")
                Canvas(Modifier.size(300.dp).semantics { contentDescription = description }
                    .background(colors.qr("surface.base"))) {
                    val step = floor(minOf(size.width, size.height) / modules.side)
                    val origin = Offset((size.width - step * modules.side) / 2, (size.height - step * modules.side) / 2)
                    for (y in 0 until modules.side) for (x in 0 until modules.side) if (modules.isDark(x, y)) {
                        drawRect(colors.qr("glyph.primary"), origin + Offset(x * step, y * step), Size(step, step))
                    }
                }
                Text(s.text("bipe_web_signin_step_open"))
                Text(s.text("bipe_web_signin_step_path", s.text("bipe_tab_you"), s.text("bipe_settings_account"),
                    s.text("bipe_linked_devices_title"), s.text("bipe_linked_devices_link_computer")))
                Text(description)
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.payload.copyText())) }) { Text(s.text("bipe_app_link_phone_copy")) }
            }
            is PhoneLinkPhase.CheckCode -> {
                Text(s.text("bipe_web_signin_check_code_title"))
                OutlinedTextField(value = digits, onValueChange = { digits = it.filter { c -> c in '0'..'9' }.take(2) },
                    singleLine = true, isError = state.invalidInput,
                    textStyle = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(180.dp).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && digits.length == 2) {
                            linking.attempt.submitCheckCode(digits); true
                        } else false
                    }, label = { Text(s.text("bipe_web_signin_check_code_title")) })
                BipeButton(s.text("bipe_web_signin_check_code_send"), { linking.attempt.submitCheckCode(digits) }, enabled = digits.length == 2)
            }
            PhoneLinkPhase.SubmittingCheckCode, PhoneLinkPhase.Approving -> Text(s.text("bipe_web_signin_approve"))
            PhoneLinkPhase.ImportingKeys, PhoneLinkPhase.Linked -> Text(s.text("bipe_web_signin_keys"))
            is PhoneLinkPhase.Failed, PhoneLinkPhase.Cancelled -> {
                Text(s.text("bipe_error_generic"))
                Text(s.text("bipe_mac_linked_devices_footer"))
                BipeButton(s.text("bipe_desktop_pending_title"), { model.open(StoredSlot.Pending(linking.runtime.slot)) })
            }
        }
        TextButton(onClick = { model.cancelLink() }) { Text(s.text("bipe_app_cancel")) }
    }
}

@Composable private fun PendingRecoveryView(model: DesktopAppModel, recovery: AppPhase.Recovering) {
    val s = model.catalog
    val phase by recovery.recovery.phase.collectAsState()
    var confirmation by remember(recovery) { mutableStateOf<PendingRecoveryAction?>(null) }
    Centered {
        Text(s.text("bipe_desktop_pending_title"), style = MaterialTheme.typography.headlineMedium)
        when (val current = phase) {
            is PendingRecoveryPhase.Working -> Text(s.text("bipe_desktop_pending_working"))
            is PendingRecoveryPhase.Done -> {
                Text(s.text(if (current.remoteEnded) "bipe_desktop_pending_done_remote" else "bipe_desktop_pending_done_local"))
                BipeButton(s.text("bipe_app_close"), { model.refresh() })
            }
            PendingRecoveryPhase.Closed -> Unit
            PendingRecoveryPhase.Review, is PendingRecoveryPhase.Failed -> {
                Text(s.text("bipe_desktop_pending_body"))
                if (current is PendingRecoveryPhase.Failed) Text(s.text("bipe_desktop_pending_failed"))
                if (recovery.source is StoredSlot.Pending) {
                    BipeButton(s.text("bipe_desktop_pending_sign_out"), { model.recoverPending(PendingRecoveryAction.SIGN_OUT) })
                    TextButton(onClick = { confirmation = PendingRecoveryAction.FORGET_LOCAL }) { Text(s.text("bipe_desktop_pending_forget")) }
                } else BipeButton(s.text("bipe_desktop_pending_cleanup"), { model.recoverPending(PendingRecoveryAction.FINISH_CLEANUP) })
                TextButton(onClick = { model.refresh() }) { Text(s.text("bipe_app_cancel")) }
            }
        }
    }
    if (confirmation == PendingRecoveryAction.FORGET_LOCAL) AlertDialog(
        onDismissRequest = { confirmation = null },
        title = { Text(s.text("bipe_desktop_pending_forget_title")) },
        text = { Text(s.text("bipe_desktop_pending_forget_body")) },
        confirmButton = { BipeButton(s.text("bipe_desktop_pending_forget"), {
            confirmation = null; model.recoverPending(PendingRecoveryAction.FORGET_LOCAL)
        }) },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(s.text("bipe_app_cancel")) } },
    )
}

private enum class Destination(val key: String) { CHATS("bipe_tab_chats"), GROUPS("bipe_tab_groups"), YOU("bipe_tab_you") }

@Composable private fun Workspace(model: DesktopAppModel, account: AppPhase.Ready, colors: DesktopColors) {
    val s = model.catalog
    val profile by account.profile.collectAsState()
    var destination by remember(account) { mutableStateOf(Destination.CHATS) }
    val selections = remember(account) { mutableStateMapOf<Destination, String>() }
    val searches = remember(account) { mutableStateMapOf<Destination, String>() }
    val selected = selections[destination]
    val search = searches[destination].orEmpty()
    LaunchedEffect(account, destination, selected) {
        if (destination == Destination.YOU || (destination == Destination.GROUPS && profile.kind == AccountKind.COMPANY)) model.clearRoom()
        else if (selected == null) model.clearRoom() else model.selectRoom(selected)
    }
    var accountsOpen by remember(account) { mutableStateOf(false) }
    val load by account.list.rows.collectAsState()
    val order by account.list.favouriteOrder.collectAsState()
    val draftPreviews by account.runtime.drafts.previews.collectAsState()
    val allRows = when (val snapshot = load) { is LoadPhase.Ready -> snapshot.value; else -> emptyList() }
    val scope = if (profile.kind == AccountKind.COMPANY) ChatScope.ALL
        else if (destination == Destination.CHATS) ChatScope.DIRECT else ChatScope.GROUPS
    val sections = ChatListSections.from(allRows, order, scope, search)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(304.dp)) {
                TextButton(onClick = { accountsOpen = true }) { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = accountsOpen, onDismissRequest = { accountsOpen = false }) {
                    account.slots.forEachIndexed { index, slot ->
                        DropdownMenuItem(text = { Text(if (slot.slot == account.runtime.slot) profile.name else s.text("bipe_tab_you") + " ${index + 1}") },
                            onClick = { accountsOpen = false; if (slot.slot != account.runtime.slot) model.open(slot) })
                    }
                    DropdownMenuItem(text = { Text(s.text("bipe_linked_devices_link_computer")) }, onClick = { accountsOpen = false; model.link() })
                }
            }
            Destination.entries.forEach { mode ->
                val label = if (mode == Destination.GROUPS && profile.kind == AccountKind.COMPANY) "bipe_tab_work" else mode.key
                Tab(selected = destination == mode, onClick = { destination = mode }, modifier = Modifier.widthIn(min = 100.dp, max = 150.dp),
                    text = { Text(s.text(label)) })
            }
        }
        HorizontalDivider(color = colors["line.subtle"])
        if (destination == Destination.YOU || (destination == Destination.GROUPS && profile.kind == AccountKind.COMPANY)) {
            Centered {
                Text(profile.name, style = MaterialTheme.typography.headlineMedium)
                Text(s.text("bipe_chats_colors") + ": " + s.text("bipe_colors_${profile.palette}"))
                Text(s.text("bipe_mac_account_change_on_phone"))
                Text(s.versionLabel, style = MaterialTheme.typography.bodySmall)
                BipeButton(s.text("bipe_linked_devices_link_computer"), { model.link() })
            }
        } else Row(Modifier.weight(1f)) {
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                OutlinedTextField(search, { searches[destination] = it }, singleLine = true,
                    placeholder = { Text(s.text("bipe_app_search_placeholder")) }, modifier = Modifier.fillMaxWidth().padding(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(sections.favourites.chunked(3), key = { "favourites:${it.first().id}" }) { line ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics { contentDescription = s.text("bipe_chat_favourites") },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            line.forEach { row ->
                                val unreadLabel = s.text("bipe_chat_unread_state")
                                val label = if (row.unread > 0) "${row.name}, $unreadLabel" else row.name
                                SelectionSurface(row.id == selected, colors, { selections[destination] = row.id },
                                    Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = label }) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            val size = minOf(maxWidth * 0.8f, 92.dp)
                                            Box(Modifier.size(size)) {
                                                Avatar(row, account.runtime.media, colors, size)
                                                if (row.unread > 0) Box(Modifier.align(Alignment.TopEnd).size(20.dp)
                                                    .border(2.dp, colors["surface.base"], CircleShape).padding(2.dp)
                                                    .background(colors["accent.fill"], CircleShape))
                                            }
                                        }
                                        Text(row.name, Modifier.padding(horizontal = 4.dp, vertical = 6.dp), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    item(key = "load-state") {
                        when (load) {
                            LoadPhase.Loading -> Text(s.text("bipe_web_loading"), Modifier.padding(16.dp))
                            LoadPhase.Failed -> BipeButton(s.text("bipe_app_retry"), { model.open(account.slots.first { it.slot == account.runtime.slot }) }, Modifier.padding(16.dp))
                            is LoadPhase.Ready -> if (sections.isEmpty) Text(s.text(if (destination == Destination.CHATS) "bipe_app_chat_list_empty" else "bipe_app_groups_empty"), Modifier.padding(16.dp))
                        }
                    }
                    items(sections.history, key = { it.id }) { row ->
                        SelectionSurface(row.id == selected, colors, { selections[destination] = row.id }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(row, account.runtime.media, colors)
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    val draftPreview = if (draftPreviews.containsKey(row.id)) draftPreviews[row.id] else row.draft
                                    val preview = draftPreview?.let { s.text("bipe_app_chat_list_draft", it) } ?: row.preview
                                    preview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (row.unread > 0) Text(row.unread.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item { TextButton(onClick = { account.list.loadMore() }, modifier = Modifier.fillMaxWidth()) { Text(s.text("bipe_action_show_more")) } }
                }
            }
            VerticalDivider(color = colors["line.subtle"])
            val timeline by model.timeline.collectAsState()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (val state = timeline) {
                    SelectedTimeline.Empty -> Centered { Text(s.text("bipe_tab_chats")) }
                    is SelectedTimeline.Opening -> Centered { Text(s.text("bipe_web_loading")) }
                    is SelectedTimeline.Failed -> Centered { BipeButton(s.text("bipe_app_retry"), { selected?.let(model::selectRoom) }) }
                    is SelectedTimeline.Showing -> key(state.model) {
                        Column {
                            allRows.firstOrNull { it.id == state.model.roomId }?.let { row ->
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Avatar(row, account.runtime.media, colors)
                                    Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = colors["line.subtle"])
                            }
                            Box(Modifier.weight(1f)) { TimelineView(state.model, s, colors) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SelectionSurface(selected: Boolean, colors: DesktopColors, onSelect: () -> Unit,
                                         modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(12.dp)).selectable(selected, onClick = onSelect),
        color = if (selected) colors["accent.fill"] else colors["surface.base"],
        contentColor = if (selected) colors["ink.onAccent"] else colors["ink.primary"], content = content)
}

@Composable private fun TimelineView(model: DesktopTimeline, s: DesktopCatalog, colors: DesktopColors) {
    val load by model.rows.collectAsState()
    val composer by model.composer.state.collectAsState()
    val draft = (composer as? ComposerState.Editing)?.text.orEmpty()
    val sending by model.sendPhase.collectAsState()
    val pauses by model.pauseSeconds.collectAsState()
    val reactionAction by model.reactions.phase.collectAsState()
    var reactionPopup by remember(model) { mutableStateOf<ReactionPopup?>(null) }
    val rows = when (val snapshot = load) { is LoadPhase.Ready -> snapshot.value; else -> emptyList() }
    Column(Modifier.fillMaxSize().chatBackdrop(s, colors)) {
        TextButton(onClick = { model.loadEarlier() }) { Text(s.text("bipe_action_show_more")) }
        when (load) {
            LoadPhase.Loading -> Text(s.text("bipe_web_loading"), Modifier.padding(16.dp))
            LoadPhase.Failed -> Text(s.text("bipe_error_generic"), Modifier.padding(16.dp))
            is LoadPhase.Ready -> Unit
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.asReversed(), key = { it.id }) { message ->
                val warning = message.safety as? MessageSafety.Warning
                val seconds = pauses[message.id] ?: if (warning?.kind?.registryId() in s.moneyPauseKinds) s.moneyPauseSeconds else 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = if (message.own) Alignment.End else Alignment.Start) {
                        Surface(Modifier.widthIn(max = 540.dp), shape = RoundedCornerShape(18.dp),
                            color = colors[if (message.own) "bubble.own" else "bubble.other"], contentColor = colors["ink.primary"]) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (!message.own) Text(message.sender, style = MaterialTheme.typography.labelMedium)
                                if (seconds > 0) Text(message.body) else SelectionContainer { Text(message.body) }
                                Text(timeLabel(message.timestamp), style = MaterialTheme.typography.labelSmall)
                                if (message.delivery == MessageRow.Delivery.SENDING) Text(s.text("bipe_app_timeline_sending"), style = MaterialTheme.typography.bodySmall)
                                if (message.delivery == MessageRow.Delivery.FAILED) {
                                    if (message.canRetry) TextButton(onClick = { model.retry(message.id) }) { Text(s.text("bipe_app_timeline_failed")) }
                                    else Text(s.text("bipe_error_generic"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        MessageReactions(model, message, reactionPopup, { reactionPopup = it }, s, colors)
                        when (val safety = message.safety) {
                            is MessageSafety.Warning -> ScamWarningCard(model, message.id, safety.kind, seconds, s, colors)
                            MessageSafety.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.text("bipe_error_generic"), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { model.retrySafety(message.id) }) { Text(s.text("bipe_app_retry")) }
                            }
                            MessageSafety.Checking -> Text(s.text("bipe_web_loading"), style = MaterialTheme.typography.bodySmall)
                            MessageSafety.Clear, MessageSafety.NotApplicable -> Unit
                        }
                    }
                }
            }
        }
        if (reactionAction is ReactionActionPhase.Failed) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.text("bipe_app_reaction_failed"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.reactions.dismissFailure() }) { Text(s.text("bipe_app_close")) }
        }
        if (sending == SendPhase.Failed) Text(s.text("bipe_app_timeline_failed"), Modifier.padding(horizontal = 16.dp))
        when (val state = composer) {
            ComposerState.Loading -> Text(s.text("bipe_web_loading"), Modifier.padding(horizontal = 16.dp))
            ComposerState.LoadFailed -> DraftFailure(s.text("bipe_desktop_draft_load_error"), s, model.composer::retry)
            ComposerState.Unsupported -> Text(s.text("bipe_desktop_draft_unsupported"), Modifier.padding(horizontal = 16.dp))
            is ComposerState.Editing -> if (state.persistence == DraftPersistence.FAILED)
                DraftFailure(s.text("bipe_desktop_draft_save_error"), s, model.composer::retry)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft, model::draftChanged, placeholder = { Text(s.text("bipe_app_timeline_composer_placeholder")) },
                enabled = composer is ComposerState.Editing,
                shape = RoundedCornerShape(24.dp), maxLines = 8, modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed && composer is ComposerState.Editing) { model.send(); true } else false
                })
            BipeButton(s.text("bipe_app_timeline_send"), model::send, enabled = draft.isNotBlank() && sending != SendPhase.Sending)
        }
    }
    ReactionDialog(model, reactionPopup, { reactionPopup = null }, s)
}

@Composable private fun DraftFailure(message: String, s: DesktopCatalog, retry: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = retry) { Text(s.text("bipe_app_retry")) }
    }
}

private fun timeLabel(timestamp: Long): String = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
