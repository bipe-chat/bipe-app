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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Spec account-switcher. A long press opens the menu without selecting a different tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcher(tabs: AppTabsModel, fallbackMedia: MediaLoader, onNewAccount: () -> Unit) {
    val state by tabs.state.collectAsState()
    ModalBottomSheet(onDismissRequest = tabs::dismissAccountSwitcher, containerColor = BipeTheme.colors.surfaceBase) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag(BipeScreen.ACCOUNT_SWITCHER.id)) {
            Text(stringResource(R.string.bipe_app_account_switcher_title), style = MaterialTheme.typography.titleLarge,
                color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(16.dp))
            AccountRows(tabs, fallbackMedia)
            if (state.canAddAccount) {
                TextButton(onClick = { tabs.dismissAccountSwitcher(); onNewAccount() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.bipe_app_account_new))
                }
            }
        }
    }
    LaunchedEffect(tabs) { tabs.refreshProfiles() }
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name, account.avatarUrl, tabs.media(account.userId) ?: fallbackMedia, userId = account.userId, isCompany = account.isCompany)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = BipeTheme.colors.inkPrimary)
                account.handle?.let { Text("@$it", color = BipeTheme.colors.inkSecondary, style = MaterialTheme.typography.bodyMedium) }
            }
            if (account.hasUnread) Box(Modifier.size(8.dp).background(BipeTheme.colors.accentFill, CircleShape))
            if (account.isCurrent) Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = BipeTheme.colors.accentInk)
        }
    }
}
