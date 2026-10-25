// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.BlockPersonDialog
import chat.bipe.app.people.PeopleService
import chat.bipe.shared.R

/**
 * The composer place of a DM (ADR 0141). A blocked person's DM stays, and the bar of spec block-person replaces
 * the composer: bipe_app_blocked_bar with bipe_action_unblock_person. Unblock opens block-person.
 */
@Composable
fun DmComposerSlot(people: PeopleService, peerUserId: String, name: String, composer: @Composable () -> Unit) {
    LaunchedEffect(people) { people.start() }
    val ignored by people.ignoredUsers.collectAsState()
    var unblocking by remember { mutableStateOf(false) }
    if (ignored?.contains(peerUserId) == true) {
        val colors = BipeTheme.colors
        Column(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.bipe_app_blocked_bar, name),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { unblocking = true }) {
                Text(stringResource(R.string.bipe_action_unblock_person), color = colors.accentInk, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        composer()
    }
    if (unblocking) BlockPersonDialog(people, peerUserId, name, onClose = { unblocking = false })
}

/**
 * Spec chat-timeline peerAway (ADR 0141): a lasting bar above the composer when the other person of a DM left
 * (bipe_dm_left with bipe_action_invite_again) or the account is gone (bipe_dm_account_gone, no button).
 */
@Composable
fun DmLeftBar(away: PeerAway, name: String, inviting: Boolean, onInviteAgain: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (away == PeerAway.ACCOUNT_GONE) stringResource(R.string.bipe_dm_account_gone) else stringResource(R.string.bipe_dm_left, name),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            modifier = Modifier.weight(1f),
        )
        if (away == PeerAway.LEFT) {
            if (inviting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onInviteAgain) {
                    Text(stringResource(R.string.bipe_action_invite_again), color = colors.accentInk, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
