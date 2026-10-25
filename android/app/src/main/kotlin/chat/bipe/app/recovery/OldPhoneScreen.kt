// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme
import chat.bipe.app.linkcomputer.QrScanner
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/**
 * Spec recovery-old-phone: the camera (and, in Debug builds and on the emulator, the paste field) reads the code of the
 * old phone; then the check code, the wait for the approval and the history.
 */
@Composable
internal fun OldPhoneScreen(flow: RecoveryFlowModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(flow) { flow.oldPhoneModel(scope) }
    val state by model.state.collectAsState()
    val colors = BipeTheme.colors
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::cameraResult)
    LaunchedEffect(model) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        model.cameraResult(allowed)
        if (!allowed) cameraPermission.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(model) { onDispose { model.cancel() } }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp).testTag(BipeScreen.RECOVERY_OLD_PHONE.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.phase) {
            OldPhoneModel.Phase.SCANNING, OldPhoneModel.Phase.FAILED, OldPhoneModel.Phase.CAMERA_DENIED -> {
                Title(stringResource(R.string.bipe_app_old_phone_title))
                Text(stringResource(R.string.bipe_recovery_qr_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.bipe_app_old_phone_where), fontWeight = FontWeight.Bold, color = colors.inkPrimary, textAlign = TextAlign.Center)
                if (state.phase == OldPhoneModel.Phase.CAMERA_DENIED) {
                    Text(stringResource(R.string.bipe_camera_permission_body), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    BipeOutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
                } else {
                    QrScanner(onCode = model::codeRead, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                }
                // Rule 5, at the moment of the scan.
                Text(stringResource(R.string.bipe_recovery_qr_never_from_others), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
                if (model.canPaste) PasteField(state, model)
                state.error?.let { error ->
                    Text(stringResource(R.string.bipe_app_old_phone_error_title), style = MaterialTheme.typography.titleMedium, color = colors.trustDangerInk)
                    Text(stringResource(error.message), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    BipeOutlinedButton(onClick = model::scanAgain) { Text(stringResource(R.string.bipe_app_link_computer_scan_again)) }
                }
            }
            OldPhoneModel.Phase.CONNECTING -> {
                CircularProgressIndicator(color = colors.accentFill)
                Text(stringResource(R.string.bipe_app_old_phone_connecting), color = colors.inkSecondary)
            }
            OldPhoneModel.Phase.SHOW_CHECK_CODE -> {
                Title(stringResource(R.string.bipe_app_old_phone_check_code_title))
                // Large; never read aloud or sent anywhere.
                Text(
                    state.checkCode.orEmpty(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.bipe_app_old_phone_check_code_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            OldPhoneModel.Phase.WAITING_FOR_APPROVAL -> {
                Title(stringResource(R.string.bipe_app_old_phone_waiting_title))
                CircularProgressIndicator(color = colors.accentFill)
                Text(stringResource(R.string.bipe_app_old_phone_waiting_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            OldPhoneModel.Phase.SYNCING -> {
                CircularProgressIndicator(color = colors.accentFill)
                Text(stringResource(R.string.bipe_app_old_phone_syncing), color = colors.inkSecondary)
            }
        }
    }
}

/** Debug builds and the emulator only (bipe-chat/bipe#608). */
@Composable
private fun PasteField(state: OldPhoneModel.State, model: OldPhoneModel) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bipe_app_old_phone_paste), style = MaterialTheme.typography.titleMedium, color = BipeTheme.colors.inkPrimary)
        OutlinedTextField(
            value = state.pasted,
            onValueChange = model::editPasted,
            placeholder = { Text(stringResource(R.string.bipe_app_old_phone_paste_hint)) },
            maxLines = 4,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = BipeTheme.colors.inkPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
        BipeButton(onClick = model::submitPasted, enabled = state.pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bipe_app_old_phone_paste_submit))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center)
}
