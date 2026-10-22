// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The biometric check (or the screen lock) before an action that hands over the account or its history: approving a
 * device, cancelling a recovery, replacing the recovery key (specs device-verification, recovery-alert, recovery-key-save).
 * [context] must be an activity.
 */
object OwnerCheck {
    /** `true` when the owner passed. A phone without a screen lock passes: there is nothing to check against. */
    suspend fun confirm(context: Context, reason: String): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return true
        // Android 8 has no system prompt that also takes the screen lock.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return suspendCancellableCoroutine { continuation ->
            val builder = BiometricPrompt.Builder(context).setTitle(reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
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
    }
}

/** When this phone signed in to an account. Older recoveries are not alerts for it (spec recovery-alert). */
class SignInTimes(context: Context) {
    private val preferences = context.getSharedPreferences("bipe.signin", Context.MODE_PRIVATE)

    fun value(userId: String): Long = preferences.getLong(userId, 0L)

    fun record(userId: String) {
        preferences.edit().putLong(userId, System.currentTimeMillis()).apply()
    }
}
