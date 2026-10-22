// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.app.Application
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.notifications.PhoneNotifications
import chat.bipe.app.notifications.PushRegistration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Holds the account for the life of the process, so the session outlives an activity. */
class BipeApplication : Application() {
    val scope = MainScope()
    val accountStart: AccountStartModel by lazy { AccountStartModel(this, scope) }

    /** Spec push-registration. */
    val push: PushRegistration by lazy { PushRegistration(this, scope) }

    /** Spec notification-service. */
    val notifications: PhoneNotifications by lazy { PhoneNotifications(this) }

    /** Action open of a notification: the chat that the home screen opens next. */
    val roomToOpen = MutableStateFlow<String?>(null)

    /**
     * Action pushReceived. The push transport calls this with the room ID and the event ID of a
     * content-free push; the app opens the account if the push woke the process.
     */
    fun pushReceived(roomId: String, eventId: String) {
        accountStart.start()
        scope.launch {
            val session = accountStart.awaitSession() ?: return@launch
            notifications.pushReceived(session, roomId, eventId)
        }
    }
}
