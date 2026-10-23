// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.people.PeopleLinks
import chat.bipe.app.account.AccountStartScreen
import chat.bipe.app.notifications.PhoneNotifications
import chat.bipe.app.settings.AppLockGate
import chat.bipe.app.settings.AppLockModel

class MainActivity : ComponentActivity() {
    private val accountStart: AccountStartModel
        get() = (application as BipeApplication).accountStart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readInviteLink(intent)
        readDevAccount(intent)
        readOpenChat(intent)
        accountStart.start()
        setContent {
            BipeTheme {
                AppLockGate {
                    AccountStartScreen(accountStart, (application as BipeApplication).appTabs)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInviteLink(intent)
        readDevAccount(intent)
        readOpenChat(intent)
    }

    /** Spec notification-service, action open: a tap on a notification opens its chat. */
    private fun readOpenChat(intent: Intent?) {
        if (intent?.action != PhoneNotifications.ACTION_OPEN_CHAT) return
        val roomId = intent.getStringExtra(PhoneNotifications.EXTRA_ROOM_ID) ?: return
        (application as BipeApplication).roomToOpen.value = roomId
    }

    override fun onResume() {
        super.onResume()
        accountStart.resume()
    }

    override fun onStop() {
        super.onStop()
        // Spec app-lock, action lock: covers the app at once when it goes to the background.
        AppLockModel.get(this).onBackground()
    }

    /** A bipe.chat/convite/<code> link (onboarding-welcome) or a bipe.chat/recuperar/#<token> link (recovery-email). */
    private fun readInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // A profile link waits in PeopleLinks until the home screen shows (spec profile-link-open).
        if (PeopleLinks.offer(uri)) return
        accountStart.openLink(uri)
    }

    /**
     * Debug builds only: a dev test account from the launch intent (ADR 0147, bipe-chat/bipe#552), for example
     * `adb shell am start -n chat.bipe.app.next.debug/chat.bipe.app.MainActivity --es bipe.dev.user @name:bipe.net.br --es bipe.dev.password ...`.
     * The app keeps only the SDK session, never the password. The extras are removed after reading.
     */
    private fun readDevAccount(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        val user = intent.getStringExtra(EXTRA_DEV_USER)
        val password = intent.getStringExtra(EXTRA_DEV_PASSWORD)
        intent.removeExtra(EXTRA_DEV_USER)
        intent.removeExtra(EXTRA_DEV_PASSWORD)
        if (!user.isNullOrBlank() && !password.isNullOrEmpty()) {
            accountStart.useDevAccount(AccountStartModel.DevAccount(user, password))
        }
    }

    private companion object {
        const val EXTRA_DEV_USER = "bipe.dev.user"
        const val EXTRA_DEV_PASSWORD = "bipe.dev.password"
    }
}
