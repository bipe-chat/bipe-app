// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.app.Application
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.shared.R
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.shared.BipeNotificationOpen
import chat.bipe.shared.BipePushMessage
import chat.bipe.app.notifications.PhoneNotifications
import chat.bipe.app.notifications.PushRegistration
import chat.bipe.app.notifications.PushTransports
import kotlinx.coroutines.MainScope
import chat.bipe.app.pixcheck.PixPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform

/** Holds the accounts and the tabs for the life of the process, so the sessions outlive an activity. */
class BipeApplication : Application() {
    val scope = MainScope()
    val accountStart: AccountStartModel by lazy { AccountStartModel(this, scope) }
    val appTabs: AppTabsModel by lazy { AppTabsModel(accountStart, scope, getString(R.string.bipe_app_timeline_undecryptable)) }

    /** Spec push-registration. */
    val push: PushRegistration by lazy { PushRegistration(this, scope) }

    /** Spec notification-service. */
    val notifications: PhoneNotifications by lazy { PhoneNotifications(this) }

    /** Action open of a notification: the chat that the home screen opens next. */
    val roomToOpen = MutableStateFlow<BipeNotificationOpen?>(null)

    /** A tap on an approval nudge (bipe-chat/bipe#628): the account whose conversation list the app shows next. */
    val accountToOpen = MutableStateFlow<String?>(null)

    /** Spec pix-check-result, action sendToChat: the preview the home screen offers next. */
    val pixPreview = MutableStateFlow<PixPreview?>(null)

    /** Spec share-to-chat: the share the home screen offers next. */
    val pendingShare = MutableStateFlow<chat.bipe.app.share.PendingShare?>(null)

    /** Spec govbr-verify: the open gov.br check of this app run, which a signed PDF from the share sheet finds. */
    val govbr = chat.bipe.app.voce.GovbrInbox()

    private var watchingAccounts = false

    override fun onCreate() {
        super.onCreate()
        // Spec app-colors: the stored palette before the first screen.
        chat.bipe.app.common.AppTheme.load(this)
        // Spec incoming-call: the missed calls of the chat list previews.
        chat.bipe.app.calls.MissedCalls.init(this)
        // Before any SDK call. On Android, initPlatform is the only place where the SDK sets up rustls-platform-verifier:
        // without it the first HTTPS request (the login) panics, and Kotlin gets InternalException. It also sends SDK
        // warnings, errors and panics to logcat.
        initPlatform(
            TracingConfiguration(
                logLevel = LogLevel.WARN,
                traceLogPacks = emptyList(),
                extraTargets = emptyList(),
                writeToStdoutOrSystem = true,
                writeToFiles = null,
                sentryConfig = null,
            ),
            useLightweightTokioRuntime = false,
        )
        // Spec data-use: the media counters follow the network in use.
        chat.bipe.app.media.DataUseMeter.start(this)
        // FCM in the Play flavour (ADR 0150), before a push can wake the process; none in the foss flavour.
        push.transport = PushTransports.create(this)
    }

    /**
     * A data message of the push transport (Sygnal, event_id_only). The random account route names the account on this
     * phone; a push without an event only changed the unread count (a read on another device, for example).
     */
    fun pushDataReceived(data: Map<String, String>) {
        when (val message = BipePushMessage.parse(data, notifications::accountFor)) {
            is BipePushMessage.Event -> pushReceived(message.accountUserId, message.roomId, message.eventId)
            is BipePushMessage.CountChanged -> scope.launch {
                accountStart.start()
                val session = accountStart.awaitSession(message.accountUserId) ?: return@launch
                notifications.countChanged(session)
            }
            is BipePushMessage.DeviceApproval -> notifications.deviceApproval(message.accountUserId)
            BipePushMessage.Ignored -> Unit
        }
    }

    /** accountAvailable of spec pix-check-result, kept for PixCheckActivity, which starts no account. */
    fun watchAccountsForPixCheck() {
        if (watchingAccounts) return
        watchingAccounts = true
        scope.launch {
            var hadAccount = false
            accountStart.sessions.collect { sessions ->
                PixPreview.setAccountAvailable(this@BipeApplication, sessions.isNotEmpty())
                // Spec share-to-chat: signed out, nothing is kept and the sharing shortcuts go.
                if (sessions.isEmpty() && hadAccount) {
                    chat.bipe.app.share.ShareShortcuts.clear(this@BipeApplication)
                    pendingShare.value?.delete()
                    pendingShare.value = null
                }
                hadAccount = sessions.isNotEmpty()
            }
        }
    }

    /**
     * Action pushReceived. The push transport calls this with the room ID and the event ID of a
     * content-free push; the app opens the account if the push woke the process.
     */
    fun pushReceived(accountUserId: String?, roomId: String, eventId: String) {
        val target = BipeNotificationTarget.create(accountUserId, roomId)
        if (target == null) {
            notifications.unownedPush()
            return
        }
        accountStart.start()
        scope.launch {
            val session = accountStart.awaitSession(target.accountUserId) ?: return@launch
            notifications.pushReceived(session, roomId, eventId)
        }
    }
}
