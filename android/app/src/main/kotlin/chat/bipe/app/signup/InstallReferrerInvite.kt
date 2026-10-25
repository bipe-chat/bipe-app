// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.content.Context
import chat.bipe.shared.BipeInstallReferrer
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The invite code of the Play install referrer (spec onboarding-welcome, bipeinstallreferrer; ADR 0154), read once per
 * install. After an answer from the store the app never reads it again, so an invite that was used or dismissed does not
 * come back. No answer (the Play Store app is busy or updating): the next start asks again.
 */
class InstallReferrerInvite(
    context: Context,
    private val source: BipeInstallReferrer = InstallReferrers.create(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun code(): String? {
        if (prefs.getBoolean(KEY_DONE, false)) return null
        val result = withTimeoutOrNull(TIMEOUT_MS) { source.read() } ?: BipeInstallReferrer.Result.TryLater
        return when (result) {
            is BipeInstallReferrer.Result.Read -> {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                result.referrer?.let(BipeInstallReferrer::inviteCode)
            }
            BipeInstallReferrer.Result.Unsupported -> {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                null
            }
            BipeInstallReferrer.Result.TryLater -> null
        }
    }

    private companion object {
        const val PREFS = "bipe_install_referrer"
        const val KEY_DONE = "read"
        const val TIMEOUT_MS = 3_000L
    }
}
