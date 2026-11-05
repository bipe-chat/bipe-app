// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.settings.SettingsIconTile
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** Spec voce profile row (bipe-chat/bipe#988): avatar, name and @ in one full-width row that opens Seu perfil. */
@Composable
internal fun VoceProfileRow(account: AccountSummary, media: MediaLoader, openProfile: (() -> Unit)?) {
    val colors = BipeTheme.colors
    val hint = stringResource(R.string.bipe_settings_profile)
    ListItem(
        headlineContent = {
            Text(account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }, style = MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary)
        },
        supportingContent = account.handle?.let { handle -> @Composable { Text("@$handle", color = colors.inkSecondary) } },
        leadingContent = { Avatar(account.displayName, account.avatarUrl, media, size = 56.dp, userId = account.userId, isCompany = account.isCompany) },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = openProfile?.let { Modifier.clickable(onClickLabel = hint, onClick = it) } ?: Modifier,
    )
}

@Composable
internal fun VoceSectionHeader(title: String?) {
    if (title == null) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Text(title, style = MaterialTheme.typography.labelLarge, color = BipeTheme.colors.accentInk,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

/** A Material 3 list item for the rows of Você: the settings icon tile, title and subtitle (spec voce). */
@Composable
internal fun VoceRow(
    icon: PhosphorIcon,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = colors.inkPrimary) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text, color = colors.inkSecondary) } },
        leadingContent = { SettingsIconTile(icon) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

