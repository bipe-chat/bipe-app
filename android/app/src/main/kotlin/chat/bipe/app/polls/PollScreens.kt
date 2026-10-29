// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.polls

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.groups.GroupFooter
import chat.bipe.app.groups.GroupPeople
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.groups.Person
import chat.bipe.app.timeline.ChatTimelineModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.launch

private const val MIN_OPTIONS = 2
private const val MAX_OPTIONS = 20

/**
 * The poll card in chat-timeline (spec poll-detail notes): the question, the options, the user's vote and, when
 * results are visible, the counts. Tapping it opens poll-detail.
 */
@Composable
fun PollCard(poll: PollData) {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(PhosphorIcon.CHART_BAR.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
            Text(
                stringResource(if (poll.isEnded) R.string.bipe_app_poll_ended else R.string.bipe_app_poll_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.accentInk,
            )
        }
        Text(poll.question, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        poll.options.forEach { PollOptionLine(it, poll.totalVotes) }
        val total = poll.totalVotes
        Text(
            if (total != null) {
                stringResource(R.string.bipe_app_poll_votes, NumberFormat.getIntegerInstance().format(total))
            } else {
                stringResource(R.string.bipe_app_poll_results_hidden)
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
        )
    }
}

/** One option with its bar: the share of votes when results are visible. */
@Composable
private fun PollOptionLine(option: PollData.Option, total: Int?) {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(option.text, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            if (option.isYourVote) {
                Icon(
                    painterResource(PhosphorIcon.CHECK.drawable),
                    contentDescription = stringResource(R.string.bipe_app_poll_your_vote),
                    tint = colors.accentInk,
                    modifier = Modifier.size(14.dp),
                )
            }
            option.voteCount?.let {
                Spacer(Modifier.width(6.dp))
                Text(NumberFormat.getIntegerInstance().format(it), style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
            }
        }
        val count = option.voteCount
        if (count != null && total != null && total > 0) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.surfaceSunken, CircleShape)) {
                Box(
                    Modifier
                        .fillMaxWidth(count.toFloat() / total)
                        .height(6.dp)
                        .background(if (option.isWinner || option.isYourVote) colors.accentFill else colors.glyphSecondary, CircleShape),
                )
            }
        }
    }
}

/** Spec poll-create. Opened from the composer. */
@Composable
fun PollCreateScreen(timeline: ChatTimelineModel, editing: PollData? = null, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf(editing?.question.orEmpty()) }
    val options = remember { editing.draftOptions().toMutableStateList() }
    var hideResults by remember { mutableStateOf(editing?.hidesResults == true) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val canSend = question.isNotBlank() && filled.size >= MIN_OPTIONS && !sending
    BackHandler(enabled = !sending, onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.POLL_CREATE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(
                stringResource(if (editing == null) R.string.bipe_app_poll_create_title else R.string.bipe_poll_edit),
                onBack = onClose.takeUnless { sending },
                backIcon = PhosphorIcon.X,
            ) {
                if (sending) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                } else {
                    TextButton(
                        enabled = canSend,
                        onClick = {
                            // Action send.
                            val model = timeline.timeline ?: return@TextButton
                            sending = true
                            failed = false
                            scope.launch {
                                runCatching { model.sendBipePoll(editing?.eventId, question.trim(), filled, hideResults) }
                                    .onSuccess { onClose() }
                                    .onFailure { failed = true }
                                sending = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.bipe_app_timeline_send))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    enabled = !sending,
                    label = { Text(stringResource(R.string.bipe_app_poll_question)) },
                    placeholder = { Text(stringResource(R.string.bipe_app_poll_question_placeholder)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                options.forEachIndexed { index, text ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { options[index] = it },
                            enabled = !sending,
                            singleLine = true,
                            label = { Text(stringResource(R.string.bipe_app_poll_option, NumberFormat.getIntegerInstance().format(index + 1))) },
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )
                        if (options.size > MIN_OPTIONS) {
                            IconButton(onClick = { options.removeAt(index) }, enabled = !sending) {
                                Icon(
                                    painterResource(PhosphorIcon.X.drawable),
                                    contentDescription = stringResource(R.string.bipe_app_poll_remove_option),
                                    tint = colors.glyphSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                if (options.size < MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }, enabled = !sending, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.bipe_app_poll_add_option))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.bipe_app_poll_hide_results), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = hideResults, onCheckedChange = { hideResults = it }, enabled = !sending)
                }
                GroupFooter(stringResource(R.string.bipe_app_poll_hide_results_footer))
                if (failed) {
                    GroupFooter(stringResource(R.string.bipe_app_poll_send_error), color = colors.trustDangerInk)
                }
            }
        }
    }
}

/** Spec poll-detail. Follows the timeline, so new votes show live. */
@Composable
fun PollDetailScreen(session: AccountSession, timeline: ChatTimelineModel, eventId: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val messages by timeline.messages.collectAsState()
    val poll = messages.firstOrNull { it.poll?.eventId == eventId }?.poll
    var confirmingEnd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PollData?>(null) }
    editing?.let { current ->
        PollCreateScreen(timeline, current) { editing = null }
        return
    }
    val voteError = stringResource(R.string.bipe_app_poll_vote_error)
    val endedText = stringResource(R.string.bipe_app_poll_ended_text)
    // Voters show by name (Person.displayName).
    val names by produceState(emptyMap<String, String>(), timeline.roomId) {
        val room = runCatching { session.client.getRoom(timeline.roomId) }.getOrNull()
        value = room?.let { GroupPeople.members(it, cachedOnly = true) }.orEmpty().associate { it.userId to Person.of(it).displayName }
    }
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.POLL_DETAIL.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_app_poll_title), onBack = onClose, backIcon = PhosphorIcon.X)
            if (poll == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
                return@Column
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(poll.question, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                when {
                    poll.isEnded -> GroupFooter(stringResource(R.string.bipe_app_poll_ended))
                    !poll.resultsVisible -> GroupFooter(stringResource(R.string.bipe_app_poll_results_hidden))
                }
                poll.options.forEach { option ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !poll.isEnded) {
                                // Action vote. Tapping the current vote again does nothing.
                                val pollTimeline = timeline.timeline
                                if (poll.yourVote != option.answerId && pollTimeline != null) {
                                    scope.launch {
                                        runCatching { pollTimeline.sendPollResponse(poll.eventId, listOf(option.answerId)) }
                                            .onFailure { Toast.makeText(context, voteError, Toast.LENGTH_LONG).show() }
                                    }
                                }
                            }
                            .semantics { selected = option.isYourVote }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PollOptionLine(option, poll.totalVotes)
                        if (option.isYourVote) {
                            Text(stringResource(R.string.bipe_app_poll_your_vote), style = MaterialTheme.typography.labelSmall, color = colors.accentInk)
                        }
                        if (option.isWinner) {
                            Text(stringResource(R.string.bipe_app_poll_most_votes), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.accentInk)
                        }
                        if (option.voterIds.isNotEmpty()) {
                            Text(option.voterIds.joinToString { names[it] ?: it }, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                        }
                    }
                }
                poll.totalVotes?.let {
                    GroupFooter(stringResource(R.string.bipe_app_poll_votes, NumberFormat.getIntegerInstance().format(it)))
                }
                if (poll.canEdit) {
                    TextButton(onClick = { editing = poll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.bipe_poll_edit))
                    }
                }
                if (poll.canEnd) {
                    TextButton(onClick = { confirmingEnd = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.bipe_app_poll_end), color = colors.trustDangerInk)
                    }
                }
            }
        }
    }

    if (confirmingEnd && poll != null) {
        AlertDialog(
            onDismissRequest = { confirmingEnd = false },
            text = { Text(stringResource(R.string.bipe_app_poll_end_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    // Action end.
                    confirmingEnd = false
                    val pollTimeline = timeline.timeline ?: return@TextButton
                    scope.launch { runCatching { pollTimeline.endPoll(poll.eventId, endedText) } }
                }) {
                    Text(stringResource(R.string.bipe_app_poll_end), color = colors.trustDangerInk)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingEnd = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
}
