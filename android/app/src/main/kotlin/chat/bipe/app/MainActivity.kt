// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import chat.bipe.app.common.BipeToastHost
import chat.bipe.app.common.BipeToasts
import chat.bipe.app.business.ShopClaims
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import chat.bipe.app.common.AppTheme
import chat.bipe.app.share.ShareIntake
import chat.bipe.app.voce.GovbrInbox
import chat.bipe.shared.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import chat.bipe.app.pixcheck.PixPreview
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.people.PeopleLinks
import chat.bipe.app.people.PeopleRoute
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.tabs.HomeQuickActions
import chat.bipe.app.recovery.LinkWebFlow
import chat.bipe.app.account.AccountStartScreen
import chat.bipe.app.notifications.PhoneNotifications
import chat.bipe.shared.BipeNotificationDestination
import chat.bipe.shared.BipeNotificationOpen
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.app.settings.AppLockGate
import chat.bipe.app.settings.AppLockModel

class MainActivity : ComponentActivity() {
    private val accountStart: AccountStartModel
        get() = (application as BipeApplication).accountStart

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme is the launch screen (spec account-start); the app itself draws on the plain theme.
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity keeps its intent: read a signed PDF only once. It clears the action, so the link and share
        // readers below skip it.
        if (savedInstanceState == null) readGovbrDocument(intent)
        readInviteLink(intent)
        readDevAccount(intent)
        readOpenChat(intent)
        readPixPreview(intent)
        // A recreated activity keeps its intent: read a share only once.
        if (savedInstanceState == null) readShare(intent)
        // A recreated activity keeps its intent: run a quick action only once.
        if (savedInstanceState == null) readQuickAction(intent)
        (application as BipeApplication).watchAccountsForPixCheck()
        (application as BipeApplication).watchAccountsForQuickActions()
        accountStart.start()
        setContent {
            // Android keeps this theme for the next launch, so the system screen shows the palette from Android 13.
            LaunchedEffect(AppTheme.palette) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    splashScreen.setSplashScreenTheme(AppTheme.palette.launchThemeRes)
                }
            }
            BipeTheme {
                BipeToastHost {
                    AppLockGate {
                        AccountStartScreen(accountStart, (application as BipeApplication).appTabs)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readGovbrDocument(intent)
        readInviteLink(intent)
        readDevAccount(intent)
        readOpenChat(intent)
        readPixPreview(intent)
        readShare(intent)
        readQuickAction(intent)
    }

    /** Spec app-tabs, action homeQuickAction: runs after account-start. With no account on the phone, nothing happens. */
    private fun readQuickAction(intent: Intent?) {
        val action = HomeQuickActions.from(intent) ?: return
        val app = application as BipeApplication
        app.scope.launch {
            accountStart.start()
            val phase = accountStart.phase.first { it != AccountStartModel.Phase.OPENING }
            if (phase != AccountStartModel.Phase.READY) return@launch
            when (action) {
                HomeQuickActions.Action.NEW_CHAT -> PeopleLinks.open(PeopleRoute.Contacts)
                HomeQuickActions.Action.SEARCH -> app.appTabs.selectTab(AppTabsModel.Tab.SEARCH)
                HomeQuickActions.Action.MY_QR_CODE -> PeopleLinks.open(PeopleRoute.ProfileQr)
                HomeQuickActions.Action.SWITCH_ACCOUNT -> app.appTabs.openAccountSwitcher()
            }
        }
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
                BipeToasts.show(applicationContext, R.string.bipe_share_nothing)
                return@launch
            }
            accountStart.start()
            val phase = accountStart.phase.first { it != AccountStartModel.Phase.OPENING }
            // FAILED keeps the share: the account is on the phone, and the share expires by itself.
            if (phase == AccountStartModel.Phase.NO_ACCOUNT) {
                share.delete()
                BipeToasts.show(applicationContext, R.string.bipe_share_signed_out)
                return@launch
            }
            app.pendingShare.value?.delete()
            app.pendingShare.value = share
        }
    }

    /**
     * Spec govbr-verify, action receiveDocument: a PDF shared or opened with the alias `.GovbrDocument` (build flag on only).
     * It goes to the open gov.br check and never to share-to-chat. The action is cleared after reading.
     */
    private fun readGovbrDocument(intent: Intent?) {
        if (intent == null || !GovbrInbox.isDocumentIntent(intent)) return
        val uri = GovbrInbox.documentUri(intent)
        intent.action = null
        val app = application as BipeApplication
        app.scope.launch {
            val pdf = uri?.let { GovbrInbox.read(applicationContext, it) } ?: ByteArray(0)
            app.govbr.receive(applicationContext, pdf)
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
        val kind = intent.getStringExtra(PhoneNotifications.EXTRA_KIND)
        if (kind in PhoneNotifications.ACCOUNT_NOTICE_KINDS) {
            app.notifications.noticeOwner(intent)?.let {
                val group = if (kind == PhoneNotifications.KIND_GROUP_LINK_JOINED) {
                    BipeNotificationTarget.create(it, intent.getStringExtra(PhoneNotifications.EXTRA_ROOM_ID))
                } else {
                    null
                }
                // groupLinkJoined (bipe-chat/bipe#967): the group opens; else the owner's conversation list.
                if (group != null) {
                    app.roomToOpen.value = BipeNotificationOpen(group, BipeNotificationDestination.CHAT)
                } else {
                    app.accountToOpen.value = it
                }
                if (kind == PhoneNotifications.KIND_RECOVERY) app.recoveryToCheck.value = it
                if (kind == PhoneNotifications.KIND_INVITE_JOINED) app.invitesToRefresh.value = it
            }
            intent.removeExtra(PhoneNotifications.EXTRA_ROOM_ID)
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
        (application as BipeApplication).activityVisible = true
        accountStart.resume()
        LinkWebFlow.appResumed()
        // The signed lists (bipe-chat/bipe#645): fetch from the core when the last fetch is old.
        chat.bipe.shared.SignedLists.shared.appBecameActive()
    }

    override fun onStop() {
        super.onStop()
        (application as BipeApplication).activityVisible = false
        // Spec app-lock, action lock: covers the app at once when it goes to the background.
        AppLockModel.get(this).onBackground()
    }

    /** A bipe.chat/convite/<code> link (onboarding-welcome) or a bipe.chat/recuperar/#<token> link (recovery-email). */
    private fun readInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // A claim link waits for the account, through sign-up (ADR 0195 point 7).
        if (ShopClaims.keep(applicationContext, uri.toString())) return
        // A profile link waits in PeopleLinks until the home screen shows (spec profile-link-open).
        if (PeopleLinks.offer(uri)) return
        // A group link opens group-link-open on a signed-in phone; else onboarding takes its code (spec group-link-open).
        if (accountStart.phase.value == AccountStartModel.Phase.READY && PeopleLinks.offerGroupLink(uri)) return
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
