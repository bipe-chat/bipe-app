// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import chat.bipe.app.share.ShareIntake
import chat.bipe.shared.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import chat.bipe.app.pixcheck.PixPreview
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.people.PeopleLinks
import chat.bipe.app.recovery.LinkWebFlow
import chat.bipe.app.account.AccountStartScreen
import chat.bipe.app.notifications.PhoneNotifications
import chat.bipe.shared.BipeNotificationDestination
import chat.bipe.shared.BipeNotificationOpen
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
        readPixPreview(intent)
        // A recreated activity keeps its intent: read a share only once.
        if (savedInstanceState == null) readShare(intent)
        (application as BipeApplication).watchAccountsForPixCheck()
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
        readPixPreview(intent)
        readShare(intent)
    }

    /**
     * Spec share-to-chat, action receive: copies the items while the grant exists, then the home screen shows the
     * chat picker after the app lock. With no account on the phone, a message, and nothing is kept.
     */
    private fun readShare(intent: Intent?) {
        val shared = intent?.let(ShareIntake::take) ?: return
        val app = application as BipeApplication
        app.scope.launch {
            val share = ShareIntake.read(applicationContext, shared)
            if (share == null) {
                Toast.makeText(applicationContext, R.string.bipe_share_nothing, Toast.LENGTH_LONG).show()
                return@launch
            }
            accountStart.start()
            val phase = accountStart.phase.first { it != AccountStartModel.Phase.OPENING }
            // FAILED keeps the share: the account is on the phone, and the share expires by itself.
            if (phase == AccountStartModel.Phase.NO_ACCOUNT) {
                share.delete()
                Toast.makeText(applicationContext, R.string.bipe_share_signed_out, Toast.LENGTH_LONG).show()
                return@launch
            }
            app.pendingShare.value?.delete()
            app.pendingShare.value = share
        }
    }

    /** Spec pix-check-result, action sendToChat: the preview from PixCheckActivity waits for chat choice and Send. */
    private fun readPixPreview(intent: Intent?) {
        PixPreview.from(intent)?.let { (application as BipeApplication).pixPreview.value = it }
    }

    /** Spec notification-service, action open: a tap on a notification opens its chat. */
    private fun readOpenChat(intent: Intent?) {
        if (intent?.action != PhoneNotifications.ACTION_OPEN_CHAT) return
        val app = application as BipeApplication
        if (intent.getStringExtra(PhoneNotifications.EXTRA_KIND) == PhoneNotifications.KIND_DEVICE_APPROVAL) {
            app.notifications.approvalAccount(intent)?.let { app.accountToOpen.value = it }
            intent.removeExtra(PhoneNotifications.EXTRA_KIND)
            intent.removeExtra(PhoneNotifications.EXTRA_ACCOUNT_ROUTE)
            intent.removeExtra(PhoneNotifications.EXTRA_ACCOUNT_USER_ID)
            return
        }
        val target = app.notifications.target(intent) ?: return
        app.roomToOpen.value = BipeNotificationOpen(target, BipeNotificationDestination.fromKind(intent.getStringExtra(PhoneNotifications.EXTRA_KIND)))
        intent.removeExtra(PhoneNotifications.EXTRA_KIND)
        intent.removeExtra(PhoneNotifications.EXTRA_ACCOUNT_ROUTE)
        intent.removeExtra(PhoneNotifications.EXTRA_ACCOUNT_USER_ID)
        intent.removeExtra(PhoneNotifications.EXTRA_ROOM_ID)
    }

    override fun onResume() {
        super.onResume()
        accountStart.resume()
        LinkWebFlow.appResumed()
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
