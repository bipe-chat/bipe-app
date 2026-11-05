// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context

/**
 * Whether this phone answers for a work account as an attendant. Kept on the phone, so that a refused token still tells
 * a removed person from an owner who must sign in again (spec company-team).
 */
object CompanyRole {
    private fun prefs(context: Context) = context.getSharedPreferences("bipe.team", Context.MODE_PRIVATE)

    fun isAttendant(context: Context, userId: String): Boolean = prefs(context).getBoolean(userId, false)

    fun remember(context: Context, userId: String, role: String?) {
        prefs(context).edit().apply { if (role == "attendant") putBoolean(userId, true) else remove(userId) }.apply()
    }
}
