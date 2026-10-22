// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.delay

/** Spec recovery-key-enter: type or paste the recovery key to bring back the message history. */
@Composable
fun RecoveryKeyEnterScreen(status: EncryptionStatusModel, close: () -> Unit, noKey: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(status) { RecoveryKeyEnterModel(status, scope) }
    val key by model.key.collectAsState()
    val phase by model.phase.collectAsState()
    val colors = BipeTheme.colors
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Action submit: on success the screen shows done and closes.
    LaunchedEffect(phase) {
        if (phase == RecoveryKeyEnterModel.Phase.DONE) {
            delay(2_000)
            close()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().testTag(BipeScreen.RECOVERY_KEY_ENTER.id), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.bipe_app_recovery_key_enter_title), style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_app_recovery_key_enter_body), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        OutlinedTextField(
            value = key,
            onValueChange = model::editKey,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            enabled = phase != RecoveryKeyEnterModel.Phase.CHECKING && phase != RecoveryKeyEnterModel.Phase.DONE,
            isError = phase == RecoveryKeyEnterModel.Phase.WRONG_KEY,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.inkPrimary,
                unfocusedTextColor = colors.inkPrimary,
                focusedBorderColor = colors.accentFill,
                unfocusedBorderColor = colors.lineSubtle,
                errorBorderColor = colors.trustDangerLine,
                cursorColor = colors.accentFill,
            ),
        )
        when (phase) {
            RecoveryKeyEnterModel.Phase.EDITING -> Unit
            RecoveryKeyEnterModel.Phase.CHECKING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.bipe_app_recovery_key_checking), color = colors.inkSecondary)
            }
            RecoveryKeyEnterModel.Phase.WRONG_KEY -> Text(stringResource(R.string.bipe_app_recovery_key_wrong), color = colors.trustDangerInk)
            RecoveryKeyEnterModel.Phase.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.bipe_app_recovery_key_enter_done), color = colors.inkPrimary)
            }
            RecoveryKeyEnterModel.Phase.FAILED -> Text(stringResource(R.string.bipe_error_network), color = colors.trustDangerInk)
        }
        Button(onClick = model::submit, enabled = model.canSubmit(key, phase), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bipe_action_continue))
        }
        TextButton(onClick = noKey, enabled = phase != RecoveryKeyEnterModel.Phase.CHECKING, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bipe_app_recovery_key_none), color = colors.accentInk)
        }
    }
}
