// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import chat.bipe.app.account.AccountSession
import chat.bipe.shared.account.BipeWorkHours
import chat.bipe.shared.account.workHours
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps this phone's copy of a work account's opening hours (spec work-hours notes, bipe-chat/bipe#511), so a push
 * decides without a request. A personal account answers 404: no copy, it rings.
 */
object WorkHoursSync {
    suspend fun refresh(session: AccountSession, api: BipeApiClient, notifications: PhoneNotifications): BipeWorkHours? {
        try {
            val hours = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.workHours(it) }
            notifications.setWorkHours(session.userId, hours)
            return hours
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is BipeApiException.Server && error.statusCode == 404) notifications.setWorkHours(session.userId, null)
            // A network error keeps the last copy.
            return notifications.workHours(session.userId)
        }
    }
}
