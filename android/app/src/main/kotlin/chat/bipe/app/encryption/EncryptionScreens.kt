// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.signup.FieldFooter
import chat.bipe.app.signup.PrimaryButton
import chat.bipe.app.signup.TextButtonRow
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import org.matrix.rustcomponents.sdk.Encryption

/** encryption-status: the quiet banner above the chat list while this phone cannot read old messages. */
@Composable
fun HistoryBanner(model: EncryptionModel) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(colors.surfaceRaised, RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag(BipeScreen.ENCRYPTION_STATUS.id),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bipe_app_history_banner), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.bipe_app_utd_help),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentInk,
                    modifier = Modifier.clickable(role = Role.Button, onClick = model::openSheet),
                )
                Text(
                    stringResource(R.string.bipe_fallback_skip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.clickable(role = Role.Button, onClick = model::dismiss),
                )
            }
        }
    }
}

/** encryption-status: the sheet with the two ways to get the old messages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(model: EncryptionModel) {
    val colors = BipeTheme.colors
    val recovery by model.recoveryState.collectAsState()
    val otherDevices by model.hasOtherDevices.collectAsState()
    ModalBottomSheet(onDismissRequest = { model.sheetOpen.value = false }, containerColor = colors.surfaceBase) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.bipe_app_history_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            val canUseKey = model.canUseRecoveryKey(recovery)
            if (otherDevices || canUseKey) {
                Text(stringResource(R.string.bipe_recovery_history_note), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                if (otherDevices) PrimaryButton(stringResource(R.string.bipe_app_history_approve), onClick = model::approveWithDevice)
                if (canUseKey) TextButtonRow(stringResource(R.string.bipe_app_history_use_key), onClick = model::useRecoveryKey)
            } else {
                Text(stringResource(R.string.bipe_app_history_none), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
            }
        }
    }
}

/** recovery-key-save from the fallback prompt: a card above the chat list for an account without a key. */
@Composable
fun RecoveryKeyPromptCard(model: EncryptionModel) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(colors.accentWash, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_fallback_recovery_key), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_fallback_recovery_key_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bipe_app_recovery_key_create),
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkOnAccent,
                modifier = Modifier
                    .background(colors.accentFill, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { model.showsKeySave.value = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.bipe_fallback_skip),
                style = MaterialTheme.typography.labelLarge,
                color = colors.accentInk,
                modifier = Modifier.clickable(role = Role.Button, onClick = model::dismissKeyPrompt),
            )
        }
    }
}

/** A full-screen step with a close link at the top. */
@Composable
private fun EncryptionPage(screen: BipeScreen, onClose: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().testTag(screen.id), color = BipeTheme.colors.surfaceBase) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onClose != null) {
                Text(
                    stringResource(R.string.bipe_app_close),
                    style = MaterialTheme.typography.titleSmall,
                    color = BipeTheme.colors.accentInk,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClose),
                )
            }
            content()
        }
    }
}

/** Spec recovery-key-save. */
@Composable
fun RecoveryKeySaveScreen(encryption: Encryption, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(encryption) { RecoveryKeySaveModel(context.applicationContext, encryption, scope) }
    val phase by model.phase.collectAsState()
    val progress by model.progress.collectAsState()
    val key by model.recoveryKey.collectAsState()
    val copied by model.copied.collectAsState()
    val busy = phase == RecoveryKeySaveModel.Phase.SHOW_KEY || phase == RecoveryKeySaveModel.Phase.CREATING
    // The key must not be lost by a stray back gesture.
    BackHandler { if (!busy) onClose() }
    EncryptionPage(BipeScreen.RECOVERY_KEY_SAVE, onClose.takeIf { !busy }) {
        Text(stringResource(R.string.bipe_account_recovery_key), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        when (phase) {
            RecoveryKeySaveModel.Phase.INTRO, RecoveryKeySaveModel.Phase.FAILED -> {
                val replacing = model.replacing
                Text(
                    stringResource(if (replacing) R.string.bipe_app_recovery_key_replace_body else R.string.bipe_fallback_recovery_key_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.inkSecondary,
                )
                if (phase == RecoveryKeySaveModel.Phase.FAILED) FieldFooter(stringResource(R.string.bipe_error_generic), isError = true)
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(if (replacing) R.string.bipe_app_recovery_key_replace else R.string.bipe_app_recovery_key_create)) {
                    model.create(context)
                }
            }
            RecoveryKeySaveModel.Phase.CREATING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.bipe_app_recovery_key_creating), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                }
                if (progress == RecoveryKeySaveModel.Progress.ROOM_KEY_UPLOAD_ERROR) {
                    FieldFooter(stringResource(R.string.bipe_error_generic), isError = true)
                }
            }
            RecoveryKeySaveModel.Phase.SHOW_KEY -> {
                val grouped = model.groupedKey(key)
                Text(stringResource(R.string.bipe_app_recovery_key_show_body), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                Text(
                    grouped,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkPrimary,
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(if (copied) R.string.bipe_app_copied else R.string.bipe_app_copy),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.accentInk,
                        modifier = Modifier.clickable(role = Role.Button, onClick = model::copy),
                    )
                    val shareTitle = stringResource(R.string.bipe_app_share)
                    Text(
                        shareTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.accentInk,
                        modifier = Modifier.clickable(role = Role.Button) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, grouped)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.bipe_app_recovery_key_saved_action), onClick = model::confirmSaved)
            }
            RecoveryKeySaveModel.Phase.SAVED -> {
                Text(stringResource(R.string.bipe_account_recovery_key_saved), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = onClose)
            }
        }
    }
}

/** Spec recovery-key-enter. [onNoKey] opens the history sheet's other way. */
@Composable
fun RecoveryKeyEnterScreen(encryption: Encryption, onClose: () -> Unit, onNoKey: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val model = remember(encryption) { RecoveryKeyEnterModel(encryption, scope) }
    val key by model.key.collectAsState()
    val phase by model.phase.collectAsState()
    BackHandler(onBack = onClose)
    EncryptionPage(BipeScreen.RECOVERY_KEY_ENTER, onClose) {
        Text(stringResource(R.string.bipe_app_recovery_key_enter_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_app_recovery_key_enter_body), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        if (phase == RecoveryKeyEnterModel.Phase.DONE) {
            Text(stringResource(R.string.bipe_app_recovery_key_enter_done), style = MaterialTheme.typography.bodyLarge, color = colors.statusSuccess)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = onClose)
        } else {
            val label = stringResource(R.string.bipe_account_recovery_key)
            BasicTextField(
                value = key,
                onValueChange = model::editKey,
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp)
                    .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (key.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                        inner()
                    }
                },
            )
            when (phase) {
                RecoveryKeyEnterModel.Phase.CHECKING -> FieldFooter(stringResource(R.string.bipe_app_recovery_key_checking))
                RecoveryKeyEnterModel.Phase.WRONG_KEY -> FieldFooter(stringResource(R.string.bipe_app_recovery_key_wrong), isError = true)
                else -> Unit
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(R.string.bipe_action_continue),
                enabled = key.isNotBlank(),
                busy = phase == RecoveryKeyEnterModel.Phase.CHECKING,
                onClick = model::submit,
            )
            TextButtonRow(stringResource(R.string.bipe_app_recovery_key_none)) {
                onClose()
                onNoKey()
            }
        }
    }
}

/** Spec device-verification: over the app while a verification runs. */
@Composable
fun DeviceVerificationScreen(model: DeviceVerificationModel) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val role by model.role.collectAsState()
    val phase by model.phase.collectAsState()
    val device by model.otherDevice.collectAsState()
    val symbols by model.symbols.collectAsState()
    BackHandler { model.cancel() }
    EncryptionPage(BipeScreen.DEVICE_VERIFICATION, null) {
        val incoming = role == DeviceVerificationModel.Role.APPROVER && phase == DeviceVerificationModel.Phase.INCOMING
        Text(
            stringResource(if (incoming) R.string.bipe_app_verify_incoming_title else R.string.bipe_app_verify_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.inkPrimary,
        )
        when (phase) {
            DeviceVerificationModel.Phase.INCOMING -> {
                device?.let { other ->
                    val name = other.deviceDisplayName ?: other.deviceId
                    val time = DateUtils.formatDateTime(context, other.firstSeenTimestamp.toLong(), DateUtils.FORMAT_SHOW_TIME)
                    Text(stringResource(R.string.bipe_app_verify_incoming_body, name, time), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                }
                FieldFooter(stringResource(R.string.bipe_device_approval_never_for_others))
            }
            DeviceVerificationModel.Phase.COMPARE -> {
                Text(stringResource(R.string.bipe_app_verify_compare), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                symbols.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { symbol ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(symbol.symbol, style = MaterialTheme.typography.headlineLarge, color = colors.inkPrimary)
                                Text(symbol.label, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            DeviceVerificationModel.Phase.DONE ->
                Text(stringResource(R.string.bipe_app_verify_done), style = MaterialTheme.typography.bodyLarge, color = colors.statusSuccess)
            DeviceVerificationModel.Phase.CANCELLED ->
                Text(stringResource(R.string.bipe_app_verify_cancelled), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            DeviceVerificationModel.Phase.MISMATCH ->
                Text(stringResource(R.string.bipe_app_verify_mismatch), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            DeviceVerificationModel.Phase.FAILED ->
                Text(stringResource(R.string.bipe_error_generic), style = MaterialTheme.typography.bodyLarge, color = colors.trustDangerInk)
            else -> {
                Text(
                    stringResource(if (role == DeviceVerificationModel.Role.NEW_PHONE) R.string.bipe_app_verify_body else R.string.bipe_app_verify_waiting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.inkSecondary,
                )
                CircularProgressIndicator(color = colors.accentFill)
            }
        }
        Spacer(Modifier.weight(1f))
        when (phase) {
            DeviceVerificationModel.Phase.INCOMING -> {
                PrimaryButton(stringResource(R.string.bipe_app_verify_approve)) { model.accept(context) }
                TextButtonRow(stringResource(R.string.bipe_app_cancel), onClick = model::decline)
            }
            DeviceVerificationModel.Phase.COMPARE -> {
                PrimaryButton(stringResource(R.string.bipe_app_verify_match), onClick = model::match)
                TextButtonRow(stringResource(R.string.bipe_app_verify_no_match), onClick = model::noMatch)
            }
            DeviceVerificationModel.Phase.DONE, DeviceVerificationModel.Phase.CANCELLED,
            DeviceVerificationModel.Phase.MISMATCH, DeviceVerificationModel.Phase.FAILED ->
                PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::reset)
            else -> TextButtonRow(stringResource(R.string.bipe_app_cancel), onClick = model::cancel)
        }
    }
}
