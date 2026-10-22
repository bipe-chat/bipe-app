// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePinProblem
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.exportMyData
import chat.bipe.shared.settings.setPin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spec pin-change, phase. PIN_CHECK: the app lock PIN on a phone with no screen lock. */
enum class PinChangePhase { CONFIRM_IDENTITY, PIN_CHECK, ENTERING, SAVING, FAILED, DONE }

/** Spec pin-change: changes the account PIN after the owner check. No old PIN is needed (ADR 0057). */
@Composable
fun PinChangeScreen(model: SettingsModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lock = remember { AppLockModel.get(context) }
    val setup = remember { PinSetupState() }
    var phase by remember { mutableStateOf(PinChangePhase.CONFIRM_IDENTITY) }
    var error by remember { mutableStateOf<Int?>(null) }
    var savedPin by remember { mutableStateOf<String?>(null) }
    val reason = stringResource(R.string.bipe_pin_change_biometric_reason)
    BackHandler(enabled = phase != PinChangePhase.SAVING, onBack = onClose)

    fun save(pin: String) {
        savedPin = pin
        phase = PinChangePhase.SAVING
        scope.launch {
            try {
                model.authorized { model.api.setPin(pin, it) }
                // One account on the phone and the lock on: the new account PIN is the app lock PIN too (ADR 0086).
                if (lock.store.isOn) withContext(Dispatchers.Default) { lock.store.setPin(pin) }
                view.bipeHaptic(BipeHaptic.Success)
                phase = PinChangePhase.DONE
            } catch (failure: BipeApiException) {
                if (failure is BipeApiException.Server && failure.code == BipeApiErrorCode.PIN_WEAK) {
                    setup.reset((failure.reason?.let(BipePinProblem::fromId) ?: BipePinProblem.SEQUENCE).localizedMessage(context))
                    phase = PinChangePhase.ENTERING
                } else {
                    error = when {
                        failure is BipeApiException.Network -> R.string.bipe_error_network
                        failure is BipeApiException.Server && failure.code == BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        else -> R.string.bipe_error_generic
                    }
                    phase = PinChangePhase.FAILED
                }
            }
        }
    }

    // Action confirmIdentity. A phone with no screen lock uses the app lock PIN when the lock is on; else the screen
    // closes (fail closed, as in data-export).
    LaunchedEffect(Unit) {
        when {
            OwnerCheck.canCheckOwner(context) -> if (OwnerCheck.owner(context, reason)) phase = PinChangePhase.ENTERING else onClose()
            lock.store.isOn -> phase = PinChangePhase.PIN_CHECK
            else -> onClose()
        }
    }

    val colors = BipeTheme.colors
    when (phase) {
        PinChangePhase.CONFIRM_IDENTITY -> Surface(Modifier.fillMaxSize().testTag(BipeScreen.PIN_CHANGE.id), color = colors.surfaceBase) {}
        PinChangePhase.PIN_CHECK -> AppLockPinCheck(stringResource(R.string.bipe_pin_change_title), onPass = { phase = PinChangePhase.ENTERING }, onCancel = onClose)
        PinChangePhase.ENTERING, PinChangePhase.SAVING -> PinPad(
            title = setup.title(context),
            pin = setup.pin,
            modifier = Modifier.testTag(BipeScreen.PIN_CHANGE.id),
            message = setup.message(context),
            error = setup.problem,
            isBusy = phase == PinChangePhase.SAVING,
            onDigit = { digit -> setup.type(context, view, digit, ::save) },
            onDelete = setup::delete,
        )
        PinChangePhase.FAILED -> MessageScreen(
            icon = PhosphorIcon.WARNING_CIRCLE,
            text = stringResource(error ?: R.string.bipe_error_generic),
            tag = BipeScreen.PIN_CHANGE.id,
        ) {
            // Action retry: the same PIN while the screen is open.
            Button(onClick = { savedPin?.let(::save) }) { Text(stringResource(R.string.bipe_app_retry)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) }
        }
        PinChangePhase.DONE -> {
            MessageScreen(icon = PhosphorIcon.CHECK, text = stringResource(R.string.bipe_pin_changed), tag = BipeScreen.PIN_CHANGE.id) {}
            LaunchedEffect(Unit) {
                delay(1_500)
                onClose()
            }
        }
    }
}

@Composable
private fun MessageScreen(icon: PhosphorIcon, text: String, tag: String, buttons: @Composable () -> Unit) {
    val colors = BipeTheme.colors
    Surface(Modifier.fillMaxSize().testTag(tag), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, textAlign = TextAlign.Center)
            buttons()
        }
    }
}

/** Spec data-export: a copy of what the Bipe API stores about the user, as one JSON file (LGPD art. 18, ADR 0112). */
enum class DataExportPhase { IDLE, CONFIRM_IDENTITY, ENTER_PIN, PREPARING, FAILED }

class DataExportState {
    var phase by mutableStateOf(DataExportPhase.IDLE)
    var error by mutableStateOf<Int?>(null)
}

/** The dialogs of data-export over the privacy group. [startDataExport] runs action start. */
@Composable
fun DataExportDialogs(model: SettingsModel, state: DataExportState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = BipeTheme.colors
    when (state.phase) {
        DataExportPhase.ENTER_PIN -> Dialog(
            onDismissRequest = { state.phase = DataExportPhase.IDLE },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AppLockPinCheck(
                stringResource(R.string.bipe_privacy_export),
                onPass = { scope.launch { exportData(context, model, state) } },
                onCancel = { state.phase = DataExportPhase.IDLE },
            )
        }
        DataExportPhase.PREPARING -> Dialog(onDismissRequest = {}) {
            Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag(BipeScreen.DATA_EXPORT.id)) {
                Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.bipe_app_export_preparing), color = colors.inkPrimary)
                }
            }
        }
        DataExportPhase.FAILED -> AlertDialog(
            onDismissRequest = { state.phase = DataExportPhase.IDLE },
            title = { Text(stringResource(R.string.bipe_privacy_export)) },
            text = { Text(stringResource(state.error ?: R.string.bipe_privacy_export_error)) },
            confirmButton = { TextButton(onClick = { state.phase = DataExportPhase.IDLE }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
        else -> Unit
    }
}

/** Action start: biometrics or the screen lock; else the app lock PIN when the lock is on; else failed (fail closed). */
suspend fun startDataExport(context: Context, model: SettingsModel, state: DataExportState) {
    if (state.phase != DataExportPhase.IDLE) return
    when {
        OwnerCheck.canCheckOwner(context) -> {
            state.phase = DataExportPhase.CONFIRM_IDENTITY
            if (OwnerCheck.owner(context, context.getString(R.string.bipe_privacy_export_biometric))) {
                exportData(context, model, state)
            } else {
                state.phase = DataExportPhase.IDLE
            }
        }
        AppLockModel.get(context).store.isOn -> state.phase = DataExportPhase.ENTER_PIN
        else -> {
            state.error = R.string.bipe_privacy_export_error
            state.phase = DataExportPhase.FAILED
        }
    }
}

/** Action export: GET /v1/me/export, saved as it is in a cache folder that holds only the latest export, then shared. */
private suspend fun exportData(context: Context, model: SettingsModel, state: DataExportState) {
    state.phase = DataExportPhase.PREPARING
    try {
        val body = model.authorized { model.api.exportMyData(it) }
        val file = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "export")
            folder.deleteRecursively()
            folder.mkdirs()
            File(folder, body.filename).apply { writeBytes(body.bytes) }
        }
        state.phase = DataExportPhase.IDLE
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        val rateLimited = failure is BipeApiException.Server && failure.code == BipeApiErrorCode.RATE_LIMITED
        state.error = if (rateLimited) R.string.bipe_error_rate_limited else R.string.bipe_privacy_export_error
        state.phase = DataExportPhase.FAILED
    }
}
