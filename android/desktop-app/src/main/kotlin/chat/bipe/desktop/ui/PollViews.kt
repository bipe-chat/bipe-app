// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.polls.PollData
import chat.bipe.app.polls.draftOptions
import chat.bipe.desktop.chat.PollActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun PollPreview(poll: PollData, s: DesktopCatalog, open: () -> Unit) {
    Column(Modifier.widthIn(min = 230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(poll.question, style = MaterialTheme.typography.titleMedium)
        poll.options.forEach { option -> Text(optionLabel(option, s)) }
        if (poll.isEnded) Text(s.text("bipe_app_poll_ended"), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = open) { Text(s.text("bipe_app_poll_title")) }
    }
}

private enum class PollPhase { READY, CONFIRM_END, WORKING, FAILED }

@Composable internal fun PollDetails(actions: PollActions, poll: PollData, s: DesktopCatalog, close: () -> Unit, edit: () -> Unit) {
    var phase by remember(actions, poll.eventId) { mutableStateOf(PollPhase.READY) }
    val scope = rememberCoroutineScope()
    val names by produceState(emptyMap<String, String>(), actions, poll.eventId, poll.options.map { it.voterIds }) {
        if (!poll.resultsVisible) { value = emptyMap(); return@produceState }
        value = try { actions.voterNames(poll.eventId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
    }
    fun run(action: suspend () -> Unit) {
        if (phase == PollPhase.WORKING) return
        phase = PollPhase.WORKING
        scope.launch {
            phase = try { action(); PollPhase.READY }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PollPhase.FAILED }
        }
    }
    if (phase == PollPhase.CONFIRM_END) {
        AlertDialog(onDismissRequest = { phase = PollPhase.READY }, title = { Text(s.text("bipe_app_poll_end")) },
            text = { Text(s.text("bipe_app_poll_end_confirm")) },
            dismissButton = { TextButton(onClick = { phase = PollPhase.READY }) { Text(s.text("bipe_app_cancel")) } },
            confirmButton = { TextButton(onClick = { run { actions.end(poll.eventId, s.text("bipe_app_poll_ended_text")) } }) {
                Text(s.text("bipe_app_poll_end"))
            } })
        return
    }
    AlertDialog(onDismissRequest = { if (phase != PollPhase.WORKING) close() }, title = { Text(poll.question) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(poll.options, key = { it.answerId }) { option ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option.isYourVote, enabled = !poll.isEnded && phase != PollPhase.WORKING,
                                onClick = { if (!option.isYourVote) run { actions.vote(poll.eventId, option.answerId) } },
                                modifier = Modifier.semantics { contentDescription = optionLabel(option, s) })
                            Column(Modifier.weight(1f)) {
                                Text(optionLabel(option, s))
                                if (option.isWinner) Text(s.text("bipe_app_poll_most_votes"), style = MaterialTheme.typography.labelSmall)
                                if (option.isYourVote) Text(s.text("bipe_app_poll_your_vote"), style = MaterialTheme.typography.labelSmall)
                                val voters = option.voterIds.mapNotNull(names::get)
                                if (poll.resultsVisible && voters.isNotEmpty()) Text(voters.joinToString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (!poll.resultsVisible) Text(s.text("bipe_app_poll_results_hidden"))
                else poll.totalVotes?.let { Text(s.text("bipe_app_poll_votes", it.toString())) }
                if (poll.isEnded) Text(s.text("bipe_app_poll_ended"))
                if (phase == PollPhase.FAILED) Text(s.text("bipe_app_poll_vote_error"))
            }
        },
        dismissButton = {
            Row {
                if (poll.canEdit) TextButton(onClick = edit, enabled = phase != PollPhase.WORKING) { Text(s.text("bipe_poll_edit")) }
                if (poll.canEnd) TextButton(onClick = { phase = PollPhase.CONFIRM_END }, enabled = phase != PollPhase.WORKING) {
                    Text(s.text("bipe_app_poll_end"))
                }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = phase != PollPhase.WORKING) { Text(s.text("bipe_app_close")) } })
}

private fun optionLabel(option: PollData.Option, s: DesktopCatalog): String =
    if (option.voteCount == null) option.text else option.text + " · " + s.text("bipe_app_poll_votes", option.voteCount.toString())

@Composable internal fun PollEditor(actions: PollActions, poll: PollData?, s: DesktopCatalog, close: () -> Unit) {
    var question by remember(actions, poll?.eventId) { mutableStateOf(poll?.question.orEmpty()) }
    var options by remember(actions, poll?.eventId) { mutableStateOf(poll.draftOptions()) }
    var hides by remember(actions, poll?.eventId) { mutableStateOf(poll?.hidesResults == true) }
    var phase by remember(actions, poll?.eventId) { mutableStateOf(PollPhase.READY) }
    val busy = phase == PollPhase.WORKING
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(s.text(if (poll == null) "bipe_app_poll_create_title" else "bipe_poll_edit")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it }, enabled = !busy, label = { Text(s.text("bipe_app_poll_question")) },
                    placeholder = { Text(s.text("bipe_app_poll_question_placeholder")) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(options) { index, option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(option, { value -> options = options.mapIndexed { i, before -> if (i == index) value else before } },
                                enabled = !busy, label = { Text(s.text("bipe_app_poll_option", (index + 1).toString())) },
                                modifier = Modifier.weight(1f), singleLine = true)
                            if (options.size > 2) IconButton(enabled = !busy,
                                onClick = { options = options.filterIndexed { i, _ -> i != index } }) {
                                Icon(sharedIcon("trash"), contentDescription = s.text("bipe_app_poll_remove_option"))
                            }
                        }
                    }
                }
                if (options.size < PollActions.MAX_OPTIONS) TextButton(enabled = !busy, onClick = { options = options + "" }) {
                    Text(s.text("bipe_app_poll_add_option"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.text("bipe_app_poll_hide_results"))
                        Text(s.text("bipe_app_poll_hide_results_footer"), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(hides, onCheckedChange = { hides = it }, enabled = !busy, modifier = Modifier.semantics {
                        contentDescription = s.text("bipe_app_poll_hide_results")
                    })
                }
                if (phase == PollPhase.FAILED) Text(s.text("bipe_app_poll_send_error"))
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(s.text("bipe_app_cancel")) } },
        confirmButton = {
            TextButton(enabled = !busy && question.isNotBlank() && options.count { it.isNotBlank() } >= 2, onClick = {
                if (phase == PollPhase.WORKING) return@TextButton
                phase = PollPhase.WORKING
                scope.launch {
                    try { actions.save(poll?.eventId, question, options, hides); close() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { phase = PollPhase.FAILED }
                }
            }) { Text(s.text("bipe_app_timeline_send")) }
        },
    )
}
