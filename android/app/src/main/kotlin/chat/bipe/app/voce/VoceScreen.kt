// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.Intent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.app.tabs.AccountRows
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsHost
import chat.bipe.app.settings.SettingsRootSection
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen

/** Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID. */
@Composable
fun VoceScreen(tabs: AppTabsModel, session: AccountSession, api: BipeApiClient, media: MediaLoader, onNewAccount: () -> Unit) {
    val state by tabs.state.collectAsState()
    SettingsHost(session, api, onClose = { tabs.selectTab(AppTabsModel.Tab.CONVERSAS) }, onSignedOut = { tabs.start.completeSignOut(session.userId) },
        rootContent = { settings, openGroup ->
            Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.VOCE.id), color = BipeTheme.colors.surfaceBase) {
                LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    item { Text(stringResource(R.string.bipe_tab_you), style = MaterialTheme.typography.headlineMedium,
                        color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(16.dp)) }
                    item { state.accounts.firstOrNull { it.isCurrent }?.let { VoceHeader(it, media) } }
                    if (state.accounts.size > 1) {
                        item { Text(stringResource(R.string.bipe_app_account_switcher_title), color = BipeTheme.colors.inkSecondary, modifier = Modifier.padding(16.dp)) }
                        item { AccountRows(tabs, media) }
                    }
                    if (state.canAddAccount) item {
                        TextButton(onClick = onNewAccount, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.bipe_app_account_new))
                        }
                    }
                    SettingsRootSection(settings, openGroup)
                }
            }
        })
    LaunchedEffect(tabs) { tabs.refreshProfiles() }
}

@Composable
private fun VoceHeader(account: AccountSummary, media: MediaLoader) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.bipe_profile_share)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(account.displayName, account.avatarUrl, media, size = 88.dp, userId = account.userId, isCompany = account.isCompany)
        Text(account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        account.handle?.let { handle ->
            Text("@$handle", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://bipe.chat/@$handle")
                context.startActivity(Intent.createChooser(send, shareTitle))
            }) { Text(shareTitle) }
        }
    }
}
