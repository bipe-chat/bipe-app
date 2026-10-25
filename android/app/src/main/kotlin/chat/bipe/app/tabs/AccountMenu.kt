// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeHaptic
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen

/**
 * Spec account-switcher (ADR 0135): the Material 3 dropdown menu that a long press on Você opens, anchored to the item.
 * Each account shows its photo or initial, name and @handle, with a check on the current one; then Nova conta, which
 * opens account-new.
 */
@Composable
fun AccountMenu(tabs: AppTabsModel, fallbackMedia: MediaLoader, onNewAccount: () -> Unit) {
    val state by tabs.state.collectAsState()
    val view = LocalView.current
    DropdownMenu(
        expanded = state.showsAccountSwitcher,
        onDismissRequest = tabs::dismissAccountSwitcher,
        modifier = Modifier.testTag(BipeScreen.ACCOUNT_SWITCHER.id),
    ) {
        state.accounts.forEachIndexed { index, account ->
            val name = account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }
            val label = if (account.isCurrent) stringResource(R.string.bipe_app_account_current_a11y, name) else name
            // One group per owner and its company accounts (bipe-chat/bipe#444).
            if (index > 0 && account.ownerId == null) HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        val owner = account.ownerName
                        if (owner != null) {
                            Text(stringResource(R.string.bipe_account_company_owner, owner), style = MaterialTheme.typography.bodyMedium,
                                color = BipeTheme.colors.inkSecondary)
                        } else account.handle?.let {
                            Text("@$it", style = MaterialTheme.typography.bodyMedium, color = BipeTheme.colors.inkSecondary)
                        }
                    }
                },
                onClick = { if (tabs.switchAccount(account.userId)) view.bipeHaptic(BipeHaptic.Tick) },
                leadingIcon = {
                    BadgedBox(badge = { if (account.hasUnread && !account.isCurrent) Badge() }) {
                        Avatar(name, account.avatarUrl, tabs.media(account.userId) ?: fallbackMedia, size = 32.dp,
                            userId = account.userId, isCompany = account.isCompany)
                    }
                },
                trailingIcon = {
                    if (account.isCurrent) {
                        Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = BipeTheme.colors.accentInk)
                    }
                },
                modifier = Modifier.padding(start = if (account.ownerId != null) 24.dp else 0.dp).semantics(mergeDescendants = true) {
                    selected = account.isCurrent
                    contentDescription = label
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bipe_app_account_new)) },
            onClick = { tabs.dismissAccountSwitcher(); onNewAccount() },
            leadingIcon = { Icon(painterResource(PhosphorIcon.PLUS.drawable), contentDescription = null) },
        )
    }
    LaunchedEffect(state.showsAccountSwitcher) { if (state.showsAccountSwitcher) tabs.refreshProfiles() }
}

/** Account photos, names and handles shared by the menu and Você; never Matrix IDs. */
@Composable
fun AccountRows(tabs: AppTabsModel, fallbackMedia: MediaLoader) {
    val state by tabs.state.collectAsState()
    val view = LocalView.current
    state.accounts.forEach { account ->
        val name = account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }
        val label = if (account.isCurrent) stringResource(R.string.bipe_app_account_current_a11y, name) else name
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                selected = account.isCurrent
                contentDescription = label
            }.clickable { if (tabs.switchAccount(account.userId)) view.bipeHaptic(BipeHaptic.Tick) }
                // A company account sits under its owner (bipe-chat/bipe#444).
                .padding(start = if (account.ownerId != null) 40.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name, account.avatarUrl, tabs.media(account.userId) ?: fallbackMedia, userId = account.userId, isCompany = account.isCompany)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = BipeTheme.colors.inkPrimary)
                val owner = account.ownerName
                if (owner != null) {
                    Text(stringResource(R.string.bipe_account_company_owner, owner), color = BipeTheme.colors.inkSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                } else account.handle?.let { Text("@$it", color = BipeTheme.colors.inkSecondary, style = MaterialTheme.typography.bodyMedium) }
            }
            if (account.hasUnread) Box(Modifier.size(8.dp).background(BipeTheme.colors.accentFill, CircleShape))
            if (account.isCurrent) Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = BipeTheme.colors.accentInk)
        }
    }
}
