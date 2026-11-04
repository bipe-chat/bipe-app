// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * The Bipe PIN pad of the approval check ([OwnerCheck.approval], bipe-chat/bipe#785), for a phone with no strong
 * biometrics. [check] sends the PIN to POST /v1/me/pin/check, where wrong tries count against the account; the PIN is
 * never kept.
 */
@Composable
fun AccountPinCheck(title: String, check: suspend (String) -> Unit, onPass: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    BackHandler(enabled = !checking, onBack = onCancel)
    Box(Modifier.fillMaxSize()) {
        PinPad(
            title = title,
            pin = pin,
            message = stringResource(R.string.bipe_pin_enter_title),
            error = error,
            isBusy = checking,
            onDigit = { digit ->
                if (!checking && !blocked && pin.length < BipeRegistries.PIN_LENGTH) {
                    error = null
                    pin += digit
                    if (pin.length == BipeRegistries.PIN_LENGTH) {
                        val typed = pin
                        pin = ""
                        checking = true
                        scope.launch {
                            try {
                                check(typed)
                                checking = false
                                view.bipeHaptic(BipeHaptic.Success)
                                onPass()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: BipeApiException) {
                                checking = false
                                view.bipeHaptic(BipeHaptic.Error)
                                blocked = e is BipeApiException.Server && e.code == BipeApiErrorCode.PIN_BLOCKED
                                error = e.localizedMessage(context)
                            } catch (_: Exception) {
                                checking = false
                                view.bipeHaptic(BipeHaptic.Error)
                                error = context.getString(R.string.bipe_error_generic)
                            }
                        }
                    }
                }
            },
            onDelete = { pin = pin.dropLast(1) },
        )
        TextButton(onClick = onCancel, enabled = !checking, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Text(stringResource(R.string.bipe_app_cancel))
        }
    }
}

/**
 * [AccountPinCheck] in a full-screen dialog, for a flow that asks from a coroutine: the returned function shows the
 * dialog and returns true when the server accepted the PIN, false on cancel.
 */
@Composable
fun rememberAccountPinCheck(title: String, check: suspend (String) -> Unit): suspend () -> Boolean {
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val currentCheck by rememberUpdatedState(check)
    pending?.let { deferred ->
        Dialog(
            onDismissRequest = { deferred.complete(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            AccountPinCheck(
                title = title,
                check = { currentCheck(it) },
                onPass = { deferred.complete(true) },
                onCancel = { deferred.complete(false) },
            )
        }
    }
    return remember {
        val ask: suspend () -> Boolean = {
            pending?.complete(false)
            val deferred = CompletableDeferred<Boolean>()
            pending = deferred
            try {
                deferred.await()
            } finally {
                if (pending === deferred) pending = null
            }
        }
        ask
    }
}
