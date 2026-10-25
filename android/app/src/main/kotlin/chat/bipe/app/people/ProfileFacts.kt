// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileFactLines
import chat.bipe.shared.R

/** Spec chat-timeline: at most one profile-facts read an hour for the same person. */
private const val NOTICE_MAX_AGE_MS = 3_600_000L

/**
 * Spec chat-request and person-profile profileFacts (ADR 0049): account age, photo and name changes of a person.
 * Reads POST /v1/profile-facts once when it shows. No lines or a failed call: nothing shows.
 */
@Composable
fun ProfileFactsLines(people: PeopleService, userId: String, textAlign: TextAlign = TextAlign.Center) {
    val context = LocalContext.current
    val texts by produceState(emptyList<String>(), userId) {
        if (userId.isEmpty()) return@produceState
        val facts = people.profileFacts(userId) ?: return@produceState
        value = BipeProfileFactLines.of(facts).texts(context)
    }
    if (texts.isEmpty()) return
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        texts.forEach { text ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary, textAlign = textAlign)
        }
    }
}

/**
 * Spec chat-timeline profileNotice (ADR 0049): an amber bar above the composer of a 1:1 chat when the other person
 * changed name and photo close together, recently. It never hides a message; a failed call shows nothing.
 */
@Composable
fun ProfileNoticeBar(people: PeopleService, userId: String, name: String) {
    val notice by produceState(false, userId) {
        val facts = people.profileFacts(userId, maxAgeMs = NOTICE_MAX_AGE_MS) ?: return@produceState
        value = BipeProfileFactLines.of(facts).notice
    }
    if (!notice) return
    val colors = BipeTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.trustCautionWash)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.trustCautionLine))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(PhosphorIcon.WARNING.drawable),
                contentDescription = null,
                tint = colors.trustCautionInk,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.bipe_profile_notice_name_photo, name),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkPrimary,
            )
        }
    }
}
