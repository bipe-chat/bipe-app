// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec chat-notifications. */
@Composable
fun ChatNotificationsScreen(session: AccountSession, roomId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { ChatNotificationsModel(session, roomId, scope) }
    LaunchedEffect(model) { model.appear() }
    val colors = BipeTheme.colors
    val options by model.options.collectAsState()
    val current by model.current.collectAsState()
    val saving by model.saving.collectAsState()
    val error by model.error.collectAsState()
    val context = LocalContext.current
    val errorText = stringResource(R.string.bipe_app_notify_error)
    LaunchedEffect(error) {
        if (error) {
            Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
            model.dismissError()
        }
    }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_NOTIFICATIONS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            GroupTopBar(stringResource(R.string.bipe_settings_notifications), onBack)
            options.forEach { option ->
                val isCurrent = current == option.mode
                val enabled = !saving && option.mode != NotifyMode.MENTIONS_LEGACY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, role = Role.RadioButton) { model.choose(option.mode) }
                        .semantics { selected = isCurrent }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(option.mode.title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
                    }
                }
                option.footer?.let { GroupFooter(stringResource(it)) }
            }
        }
    }
}
