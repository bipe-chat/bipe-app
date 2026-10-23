// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** One person in group-invite-members, with the note of its status. */
@Composable
internal fun PickRow(pick: PersonPick, media: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val isSelected = pick.status == PersonPick.Status.SELECTED
    val dimmed = pick.status == PersonPick.Status.MEMBER || pick.status == PersonPick.Status.INVITED
    val note = when (pick.status) {
        PersonPick.Status.MEMBER -> stringResource(R.string.bipe_dm_invite_to_group_already_member)
        PersonPick.Status.INVITED -> stringResource(R.string.bipe_dm_invite_to_group_sent)
        PersonPick.Status.REFUSED -> stringResource(R.string.bipe_app_group_invite_refused, pick.person.displayName)
        PersonPick.Status.FAILED -> stringResource(R.string.bipe_app_group_invite_error, pick.person.displayName)
        PersonPick.Status.SELECTABLE, PersonPick.Status.SELECTED -> pick.person.handle?.let { "@$it" }
    }
    val isError = pick.status == PersonPick.Status.REFUSED || pick.status == PersonPick.Status.FAILED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !dimmed, onClick = onClick)
            .semantics { selected = isSelected }
            .alpha(if (dimmed) 0.6f else 1f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(pick.person.displayName, pick.person.avatarUrl, media, size = 40.dp, userId = pick.person.userId)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pick.person.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.trustDangerInk else colors.inkSecondary)
            }
        }
        if (isSelected) {
            Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
        }
    }
}
