// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import chat.bipe.shared.BipePhoneId
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.reportPhone
import chat.bipe.shared.runCatchingExceptions

/**
 * Theft protection (bipe-chat/bipe#268): every open account sends this phone's random ID once per app start
 * (PUT /v1/me/phone), so a theft mark covers the other accounts on the phone.
 */
object PhoneReport {
    private const val PREFERENCES = "bipe.theft"

    /** The ID is kept in the app's own preferences, the same for every account. */
    fun phoneId(context: Context): String =
        BipePhoneId.current(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    /** Failures are ignored: the next start tries again. */
    suspend fun report(context: Context, session: AccountSession, api: BipeApiClient) {
        val id = phoneId(context)
        runCatchingExceptions {
            BipeApiClient.withTokenRetry({ session.accessToken() }) { api.reportPhone(id, it) }
        }
    }
}
