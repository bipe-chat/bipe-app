// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The phone's own owner checks with the platform BiometricPrompt: biometrics, or biometrics and the screen lock.
 * Android 9 and older have no prompt that takes the screen lock, so there the app falls back to the app lock PIN.
 */
object OwnerCheck {
    /** The phone has biometrics set up (spec app-lock, biometricKind android). */
    fun hasBiometrics(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= 30) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** The phone has a screen lock and a prompt that can ask for it. */
    fun canCheckOwner(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Biometrics only. False on a fail or cancel. [cancel] is the text of the button that closes the prompt. */
    suspend fun biometrics(context: Context, title: String, cancel: String): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !hasBiometrics(context)) return false
        val builder = BiometricPrompt.Builder(context).setTitle(title)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        builder.setNegativeButton(cancel, context.mainExecutor) { _, _ -> }
        return prompt(context, builder.build())
    }

    /** Biometrics or the screen lock. False on a fail or cancel. */
    suspend fun owner(context: Context, title: String): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !canCheckOwner(context)) return false
        val builder = BiometricPrompt.Builder(context).setTitle(title)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return prompt(context, builder.build())
    }

    @RequiresApi(29)
    private suspend fun prompt(context: Context, prompt: BiometricPrompt): Boolean = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        prompt.authenticate(
            signal,
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }
}
