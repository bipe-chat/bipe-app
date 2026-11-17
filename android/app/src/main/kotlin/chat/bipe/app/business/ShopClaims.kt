// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import chat.bipe.shared.BipeProfileLink

/**
 * ADR 0195 point 7: a claim link from the shop on the web waits here until an account is signed in, also through
 * sign-up. The token moves the browser's records to the account, then it stops working.
 *
 * The link waits in app-private preferences; the app has no backup (allowBackup false). A link older than [MAX_AGE_MS]
 * is dropped (bipe-chat/bipe#1369). iOS keeps it in the keychain with the same age (ShopClaim.swift).
 */
object ShopClaims {
    /** Seven days: long enough to finish a sign-up, short enough that a lost token does not wait for ever. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS = "bipe.shopClaim"
    private const val KEY = "link"
    private const val KEY_SAVED_AT = "savedAt"

    /** `true` when [url] is a claim link, which then waits. */
    fun keep(context: Context, url: String): Boolean {
        if (BipeProfileLink.shopClaim(url) == null) return false
        prefs(context).edit().putString(KEY, url).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
        return true
    }

    fun pending(context: Context, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        val prefs = prefs(context)
        val url = prefs.getString(KEY, null) ?: return null
        // A link from a build before bipe#1369 has no time: it counts from now.
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L).takeIf { it > 0 }
            ?: now.also { prefs.edit().putLong(KEY_SAVED_AT, it).apply() }
        if (now - savedAt >= MAX_AGE_MS) {
            clear(context)
            return null
        }
        return BipeProfileLink.shopClaim(url)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).remove(KEY_SAVED_AT).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
