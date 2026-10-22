// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.shared.generated.BipeScreen

/** Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID. */
@Composable
fun VoceScreen(account: AccountSummary?, media: MediaLoader) {
    val colors = BipeTheme.colors
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.VOCE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            if (account != null) VoceHeader(account, media)
        }
    }
}

@Composable
private fun VoceHeader(account: AccountSummary, media: MediaLoader) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(account.displayName, account.avatarUrl, media, size = 88.dp)
        Text(account.displayName, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        account.handle?.let { handle ->
            Text("@$handle", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
    }
}
