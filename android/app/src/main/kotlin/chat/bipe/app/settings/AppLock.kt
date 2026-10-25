// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAppLock
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spec app-lock, action lock: the lock over the app while the lock is on. Off by default (ADR 0137). */
class AppLockModel private constructor(context: Context) {
    val store = BipeAppLock(context)
    private val _isLocked = MutableStateFlow(store.isOn)

    /** The lock screen is in front. */
    val isLocked: StateFlow<Boolean> = _isLocked
    private val _isOn = MutableStateFlow(store.isOn)

    /** The lock is on: the window keeps its content out of the app switcher. */
    val isOn: StateFlow<Boolean> = _isOn

    /** The activity went to the background: lock at once. */
    fun onBackground() {
        if (store.isOn) _isLocked.value = true
    }

    /** After a change from settings. */
    fun refresh() {
        _isOn.value = store.isOn
        if (!store.isOn) _isLocked.value = false
    }

    fun unlock() {
        _isLocked.value = false
    }

    companion object {
        @Volatile
        private var instance: AppLockModel? = null

        fun get(context: Context): AppLockModel = instance ?: synchronized(this) {
            instance ?: AppLockModel(context.applicationContext).also { instance = it }
        }
    }
}

/** Puts the lock screen over [content] and keeps the window out of the app switcher while the lock is on. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lock = remember { AppLockModel.get(context) }
    val isLocked by lock.isLocked.collectAsState()
    val isOn by lock.isOn.collectAsState()
    LaunchedEffect(isOn) {
        val activity = context as? Activity ?: return@LaunchedEffect
        // The app switcher shows no chats while the lock is on.
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!isOn)
        } else if (isOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (isLocked) {
            BackHandler {}
            AppLockScreen(lock)
        }
    }
}

@Composable
private fun AppLockScreen(lock: AppLockModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val word = biometricWord(context)
    val reason = stringResource(R.string.bipe_app_lock_biometric, word)
    val cancel = stringResource(R.string.bipe_app_cancel)
    val offersBiometrics = lock.store.biometricUnlock && OwnerCheck.hasBiometrics(context)
    val useBiometrics: () -> Unit = {
        scope.launch {
            if (OwnerCheck.biometrics(context, reason, cancel)) {
                view.bipeHaptic(BipeHaptic.Success)
                lock.unlock()
            }
        }
    }
    // When the app comes back: asks for biometrics at once when biometric unlock is on.
    LaunchedEffect(Unit) { if (offersBiometrics) useBiometrics() }
    PinPad(
        title = stringResource(R.string.bipe_app_lock_title),
        pin = pin,
        modifier = Modifier.testTag(BipeScreen.APP_LOCK.id),
        message = stringResource(R.string.bipe_pin_enter_title),
        error = if (wrong) stringResource(R.string.bipe_app_lock_wrong) else null,
        isBusy = checking,
        onBiometrics = if (offersBiometrics) useBiometrics else null,
        onDigit = { digit ->
            if (pin.length < BipeRegistries.PIN_LENGTH) {
                wrong = false
                pin += digit
                if (pin.length == BipeRegistries.PIN_LENGTH) {
                    val typed = pin
                    pin = ""
                    checking = true
                    scope.launch {
                        val match = withContext(Dispatchers.Default) { lock.store.verifyPin(typed) }
                        checking = false
                        if (match) {
                            view.bipeHaptic(BipeHaptic.Success)
                            lock.unlock()
                        } else {
                            view.bipeHaptic(BipeHaptic.Error)
                            wrong = true
                        }
                    }
                }
            }
        },
        onDelete = { pin = pin.dropLast(1) },
    )
}

/** Face ID on iOS; on Android the word of bipe_account_biometric_word_android. */
fun biometricWord(context: Context): String = context.getString(R.string.bipe_account_biometric_word_android)

/** Spec app-lock, modes turnOn and biometricOffer: the PIN setup from the appLock switch. */
@Composable
fun AppLockSetupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lock = remember { AppLockModel.get(context) }
    val setup = remember { PinSetupState() }
    var saving by remember { mutableStateOf(false) }
    var offersBiometrics by remember { mutableStateOf(false) }
    val word = biometricWord(context)
    val reason = stringResource(R.string.bipe_app_lock_biometric, word)
    val cancel = stringResource(R.string.bipe_app_cancel)
    val failed = stringResource(R.string.bipe_error_generic)
    BackHandler(enabled = !saving, onBack = onClose)
    if (offersBiometrics) {
        val colors = BipeTheme.colors
        Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.APP_LOCK.id), color = colors.surfaceBase) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(painterResource(PhosphorIcon.FINGERPRINT.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(56.dp))
                Text(
                    stringResource(R.string.bipe_app_lock_biometric_offer_title, word),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                // Action acceptBiometrics: asks once to confirm, then turns biometric unlock on.
                BipeButton(
                    onClick = {
                        scope.launch {
                            if (OwnerCheck.biometrics(context, reason, cancel)) lock.store.setBiometricUnlock(true)
                            onClose()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bipe_app_lock_biometric_offer_use, word)) }
                // Action skipBiometrics.
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_fallback_skip)) }
            }
        }
        return
    }
    PinPad(
        title = setup.title(context),
        pin = setup.pin,
        modifier = Modifier.testTag(BipeScreen.APP_LOCK.id),
        message = setup.message(context),
        error = setup.problem,
        isBusy = saving,
        onDigit = { digit ->
            setup.type(context, view, digit) { pin ->
                saving = true
                scope.launch {
                    // The PIN is stored on this phone only, as the app lock PIN (success haptic, use pin_set).
                    val saved = withContext(Dispatchers.Default) { lock.store.setPin(pin) }
                    saving = false
                    lock.refresh()
                    if (!saved) {
                        setup.reset(failed)
                    } else {
                        view.bipeHaptic(BipeHaptic.Success)
                        if (OwnerCheck.hasBiometrics(context)) offersBiometrics = true else onClose()
                    }
                }
            }
        },
        onDelete = setup::delete,
    )
}

/** Asks for the app lock PIN once, for a phone with no screen lock (app-lock turnOff, data-export enterPin). */
@Composable
fun AppLockPinCheck(title: String, onPass: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lock = remember { AppLockModel.get(context) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize()) {
        PinPad(
            title = title,
            pin = pin,
            message = stringResource(R.string.bipe_pin_enter_title),
            error = if (wrong) stringResource(R.string.bipe_pin_mismatch) else null,
            isBusy = checking,
            onDigit = { digit ->
                if (pin.length < BipeRegistries.PIN_LENGTH) {
                    wrong = false
                    pin += digit
                    if (pin.length == BipeRegistries.PIN_LENGTH) {
                        val typed = pin
                        pin = ""
                        checking = true
                        scope.launch {
                            val match = withContext(Dispatchers.Default) { lock.store.verifyPin(typed) }
                            checking = false
                            if (match) {
                                onPass()
                            } else {
                                view.bipeHaptic(BipeHaptic.Error)
                                wrong = true
                            }
                        }
                    }
                }
            },
            onDelete = { pin = pin.dropLast(1) },
        )
        TextButton(onClick = onCancel, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Text(stringResource(R.string.bipe_app_cancel))
        }
    }
}
