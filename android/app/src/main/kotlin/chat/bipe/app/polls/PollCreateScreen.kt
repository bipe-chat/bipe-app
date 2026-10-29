// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.polls

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupFooter
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.timeline.ChatTimelineModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.launch

private const val MIN_OPTIONS = 2
private const val MAX_OPTIONS = 20

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
