// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The biometric check (or the device PIN) before an approval (spec device-verification, ADR 0059 QR approval rule).
 * Platform APIs only: BiometricPrompt on Android 10 and later, the lock screen check before that.
 * A phone without a screen lock has nothing to check.
 */
@Composable
fun rememberDeviceOwnerCheck(reason: String): suspend () -> Boolean {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending[0]?.complete(result.resultCode == Activity.RESULT_OK)
        pending[0] = null
    }
    return remember(context, reason, launcher) {
        val check: suspend () -> Boolean = check@{
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard == null || !keyguard.isDeviceSecure) return@check true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return@check biometricPrompt(context, reason)
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent(null, reason) ?: return@check true
            val result = CompletableDeferred<Boolean>()
            pending[0] = result
            launcher.launch(intent)
            result.await()
        }
        check
    }
}

private suspend fun biometricPrompt(context: Context, reason: String): Boolean = suspendCancellableCoroutine { continuation ->
    val builder = BiometricPrompt.Builder(context).setTitle(reason)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val cancel = CancellationSignal()
    continuation.invokeOnCancellation { cancel.cancel() }
    builder.build().authenticate(
        cancel,
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false)
            }
        },
    )
}
