// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import chat.bipe.shared.BipeProfileLink

/**
 * ADR 0195 point 7: a claim link from the shop on the web waits here until an account is signed in, also through
 * sign-up. The token moves the browser's records to the account, then it stops working.
 */
object ShopClaims {
    private const val PREFS = "bipe.shopClaim"
    private const val KEY = "link"

    /** `true` when [url] is a claim link, which then waits. */
    fun keep(context: Context, url: String): Boolean {
        if (BipeProfileLink.shopClaim(url) == null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, url).apply()
        return true
    }

    fun pending(context: Context): Pair<String, String>? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { BipeProfileLink.shopClaim(it) }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
