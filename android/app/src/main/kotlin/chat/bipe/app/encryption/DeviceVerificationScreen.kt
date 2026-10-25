// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.delay

/** Spec device-verification: approve a new phone with a device that is already signed in. */
@Composable
fun DeviceVerificationScreen(model: DeviceVerificationModel, close: () -> Unit) {
    val role by model.role.collectAsState()
    val phase by model.phase.collectAsState()
    val otherDevice by model.otherDevice.collectAsState()
    val symbols by model.symbols.collectAsState()
    val mismatch by model.mismatch.collectAsState()
    val confirmOwner = rememberDeviceOwnerCheck(stringResource(R.string.bipe_app_verify_biometric))
    val colors = BipeTheme.colors

    // The flow ends on its own: the screen closes a moment after done.
    LaunchedEffect(phase) {
        if (phase == DeviceVerificationModel.Phase.DONE) {
            delay(2_000)
            close()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(BipeScreen.DEVICE_VERIFICATION.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (phase) {
            DeviceVerificationModel.Phase.REQUESTING, DeviceVerificationModel.Phase.WAITING_FOR_OTHER -> {
                if (role == DeviceVerificationModel.Role.NEW_PHONE) {
                    Title(stringResource(R.string.bipe_app_verify_title))
                    Body(stringResource(R.string.bipe_app_verify_body))
                }
                CircularProgressIndicator(color = colors.accentFill)
                Body(stringResource(R.string.bipe_app_verify_waiting))
                BipeOutlinedButton(onClick = { model.cancel(); close() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_cancel))
                }
            }
            DeviceVerificationModel.Phase.INCOMING, DeviceVerificationModel.Phase.CONFIRM_IDENTITY -> {
                val context = LocalContext.current
                val device = otherDevice
                val name = device?.displayName ?: device?.deviceId.orEmpty()
                val time = (device?.firstSeenAt ?: System.currentTimeMillis()).let {
                    val flags = if (DateUtils.isToday(it)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                    DateUtils.formatDateTime(context, it, flags)
                }
                Title(stringResource(R.string.bipe_app_verify_incoming_title))
                Body(stringResource(R.string.bipe_app_verify_incoming_body, name, time))
                Body(stringResource(R.string.bipe_device_approval_never_for_others))
                BipeButton(
                    onClick = { model.accept(confirmOwner) },
                    enabled = phase == DeviceVerificationModel.Phase.INCOMING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bipe_app_verify_approve))
                }
                BipeOutlinedButton(onClick = model::decline, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_cancel))
                }
            }
            DeviceVerificationModel.Phase.COMPARE, DeviceVerificationModel.Phase.WAITING_FOR_MATCH -> {
                Title(stringResource(R.string.bipe_app_verify_compare))
                SymbolGrid(symbols)
                if (phase == DeviceVerificationModel.Phase.WAITING_FOR_MATCH) {
                    CircularProgressIndicator(color = colors.accentFill)
                    Body(stringResource(R.string.bipe_app_verify_waiting))
                } else {
                    BipeButton(onClick = model::match, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_app_verify_match))
                    }
                    BipeOutlinedButton(onClick = model::noMatch, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_app_verify_no_match))
                    }
                }
            }
            DeviceVerificationModel.Phase.DONE ->
                Outcome(PhosphorIcon.CHECK_CIRCLE, colors.statusSuccess, stringResource(R.string.bipe_app_verify_done))
            DeviceVerificationModel.Phase.CANCELLED -> Outcome(
                PhosphorIcon.X,
                colors.glyphSecondary,
                stringResource(if (mismatch) R.string.bipe_app_verify_mismatch else R.string.bipe_app_verify_cancelled),
            )
            DeviceVerificationModel.Phase.FAILED ->
                Outcome(PhosphorIcon.WARNING_CIRCLE, colors.trustCautionInk, stringResource(R.string.bipe_error_generic))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = BipeTheme.colors.inkSecondary, textAlign = TextAlign.Center)
}

/** The 7 SAS symbols in rows of 4: the symbol large, its label small (spec notes). */
@Composable
private fun SymbolGrid(symbols: List<VerificationSymbol>) {
    // Two per row at large font sizes, so that every name shows in full (bipe#498).
    val perRow = if (LocalDensity.current.fontScale >= 1.5f) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        symbols.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { symbol ->
                    Column(
                        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = symbol.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(symbol.symbol, fontSize = 40.sp)
                        Text(
                            symbol.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = BipeTheme.colors.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(perRow - row.size) {
                    Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun Outcome(icon: PhosphorIcon, tint: Color, text: String) {
    Icon(painterResource(icon.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(56.dp).padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center)
}
