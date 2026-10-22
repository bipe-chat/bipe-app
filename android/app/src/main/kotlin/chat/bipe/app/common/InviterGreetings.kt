// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.Context

/**
 * The inviter of an account (ADR 0034), until the account sends them a first message. The chat with the inviter shows
 * the hint of spec signup-done while it is set. Not secret: shared preferences of this app.
 */
class InviterGreetings(context: Context, private val userId: String) {
    data class Greeting(val userId: String, val handle: String?)

    private val preferences = context.getSharedPreferences("bipe.invites", Context.MODE_PRIVATE)

    fun get(): Greeting? {
        val inviter = preferences.getString("greeting.$userId", null) ?: return null
        return Greeting(inviter, preferences.getString("greeting.handle.$userId", null))
    }

    fun set(inviterUserId: String, handle: String?) {
        preferences.edit()
            .putString("greeting.$userId", inviterUserId)
            .putString("greeting.handle.$userId", handle)
            .apply()
    }

    fun clear() {
        preferences.edit().remove("greeting.$userId").remove("greeting.handle.$userId").apply()
    }
}
