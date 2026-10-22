// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.HomeScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec account-start: no login screen (ADR 0057). Shows chat-list when the account is ready. */
@Composable
fun AccountStartScreen(model: AccountStartModel) {
    val phase by model.phase.collectAsState()
    val session = model.session
    if (phase == AccountStartModel.Phase.READY && session != null) {
        HomeScreen(session)
        return
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(BipeScreen.ACCOUNT_START.id),
        color = BipeTheme.colors.surfaceBase,
    ) {
        when (phase) {
            AccountStartModel.Phase.NO_ACCOUNT -> StartMessage(
                icon = PhosphorIcon.USER_CIRCLE,
                title = stringResource(R.string.bipe_app_start_no_account_title),
                message = stringResource(R.string.bipe_app_start_no_account_body),
            )
            AccountStartModel.Phase.FAILED -> StartMessage(
                icon = PhosphorIcon.WARNING_CIRCLE,
                title = stringResource(R.string.bipe_app_name),
                message = stringResource(R.string.bipe_app_start_error),
                onRetry = model::retry,
            )
            else -> StartMessage(
                icon = PhosphorIcon.CHAT_CIRCLE,
                title = stringResource(R.string.bipe_app_name),
                message = stringResource(R.string.bipe_app_start_loading),
                showsProgress = true,
            )
        }
    }
}

@Composable
private fun StartMessage(
    icon: PhosphorIcon,
    title: String,
    message: String,
    showsProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary, textAlign = TextAlign.Center)
        if (showsProgress) {
            CircularProgressIndicator(color = colors.accentFill)
        }
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(PhosphorIcon.ARROW_CLOCKWISE.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.bipe_app_retry))
                }
            }
        }
    }
}
