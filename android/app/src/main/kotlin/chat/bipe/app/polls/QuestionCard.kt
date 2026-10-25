// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.polls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.CompanySealDialog
import chat.bipe.app.timeline.ChatTimelineModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch

/**
 * Spec question-card (bipe-chat/bipe#244, ADR 0081): a company's structured question with large options and one tap
 * to answer. The card shows no counts: the company reads the answers.
 */
@Composable
fun QuestionCard(poll: PollData, companyName: String, seal: BipeCompanySealState, model: ChatTimelineModel) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var sendingId by remember(poll.eventId) { mutableStateOf<String?>(null) }
    var failed by remember(poll.eventId) { mutableStateOf(false) }
    var showsSeal by remember { mutableStateOf(false) }
    val chosen = poll.options.firstOrNull { it.answerId == poll.yourVote }

    // Action answer. Tapping the chosen option again does nothing; a failure keeps the old answer.
    fun answer(answerId: String) {
        val timeline = model.timeline
        if (poll.isEnded || answerId == poll.yourVote || sendingId != null || timeline == null) return
        failed = false
        sendingId = answerId
        scope.launch {
            runCatching { timeline.sendPollResponse(poll.eventId, listOf(answerId)) }.onFailure { failed = true }
            sendingId = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(BipeScreen.QUESTION_CARD.id)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clickable(role = Role.Button) { showsSeal = true }
                .semantics { heading() },
        ) {
            Icon(
                painterResource(PhosphorIcon.SEAL_CHECK.drawable),
                contentDescription = stringResource(R.string.bipe_badge_company),
                tint = colors.trustVerifiedInk,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.bipe_app_question_from, companyName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
            )
        }
        Text(poll.question, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        poll.options.forEach { option ->
            val isChosen = option.isYourVote
            val shape = RoundedCornerShape(12.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (isChosen) colors.accentFill else colors.surfaceRaised, shape)
                    .border(1.5.dp, if (isChosen) colors.accentFill else colors.inkPrimary, shape)
                    .clickable(enabled = !poll.isEnded && sendingId == null, role = Role.Button) { answer(option.answerId) }
                    .semantics { selected = isChosen }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    option.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isChosen) colors.inkOnAccent else colors.inkPrimary,
                    modifier = Modifier.weight(1f),
                )
                when {
                    sendingId == option.answerId ->
                        CircularProgressIndicator(color = colors.inkPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    isChosen -> Icon(
                        painterResource(PhosphorIcon.CHECK_CIRCLE.drawable),
                        contentDescription = null,
                        tint = colors.inkOnAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                poll.isEnded -> Text(stringResource(R.string.bipe_app_question_closed), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                chosen != null -> {
                    Text(stringResource(R.string.bipe_app_question_your_answer, chosen.text), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                    Text(stringResource(R.string.bipe_app_question_change_hint), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
                else -> Text(stringResource(R.string.bipe_app_question_hint), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            if (failed) {
                Text(stringResource(R.string.bipe_app_poll_vote_error), style = MaterialTheme.typography.bodyMedium, color = colors.trustDangerInk)
            }
        }
    }
    if (showsSeal) CompanySealDialog(seal) { showsSeal = false }
}
