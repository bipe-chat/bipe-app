// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.util.Log
import chat.bipe.app.common.AppConfig
import chat.bipe.app.settings.DeletionDates
import chat.bipe.app.BipeApplication
import chat.bipe.app.common.InviterGreetings
import chat.bipe.shared.onboarding.BipeBringPeopleMemory
import chat.bipe.app.recovery.RecoveryTokenSyncs
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.account.BipeRecoveryTokens
import chat.bipe.app.notifications.WorkHoursSync
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Session

/** What signup-done needs after a new account (spec signup-done). */
data class SignupOutcome(val inviterUserId: String?, val inviterHandle: String?)

/**
 * Spec account-start (bipe-shared product/screens/account-start.json).
 *
 * Opens every account on this phone (ADR 0086): one SDK client and session store per Matrix ID. The current account is
 * the one used last, else the oldest on the phone.
 */
class AccountStartModel(private val context: Context, private val scope: CoroutineScope) {
    val appContext: Context get() = context.applicationContext
    enum class Phase { OPENING, NO_ACCOUNT, FAILED, READY }

    /** A dev test account given to a debug build (ADR 0147: Android cannot read the fork's credential). */
    data class DevAccount(val userId: String, val password: String) {
        override fun toString() = "DevAccount($userId)"
    }

    val api = BipeApiClient(AppConfig.API_BASE_URL)
    val vault = BipeAccountVault(context)

    /** The recovery token in the synced store (Block Store in the Play build, ADR 0150). */
    val recoveryTokens = BipeRecoveryTokens(vault, RecoveryTokenSyncs.create(context), scope)
    val sessionStore = SessionStore(context)
    private val accountList = AccountList(context)

    private val _phase = MutableStateFlow(Phase.OPENING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The current account. */
    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    /** The open accounts, oldest first. */
    private val _sessions = MutableStateFlow<List<AccountSession>>(emptyList())
    val sessions: StateFlow<List<AccountSession>> = _sessions.asStateFlow()

    /** Set right after a sign-up: the app shows signup-done before chat-list. */
    private val _signupOutcome = MutableStateFlow<SignupOutcome?>(null)
    val signupOutcome: StateFlow<SignupOutcome?> = _signupOutcome.asStateFlow()

    /** The chat that chat-list opens first, once (signup-done opens the chat with the inviter). */
    var initialRoomId: String? = null
        private set

    /** The current account's session. */
    val session: AccountSession?
        get() = _sessions.value.firstOrNull { it.userId == _userId.value }

    /** A bipe.chat/convite link the app was opened with, for onboarding-welcome (action openInviteLink). */
    var pendingInviteLink: android.net.Uri? = null

    /**
     * noAccount shows recovery-start instead of onboarding-welcome: after "Já tenho conta", for a recovery link, or while
     * a kept recovery waits (spec recovery-waiting notes).
     */
    private val _showsRecovery = MutableStateFlow(false)
    val showsRecovery: StateFlow<Boolean> = _showsRecovery.asStateFlow()

    /** A bipe.chat/recuperar link (recovery-email, action openLink). */
    private val _recoveryLink = MutableStateFlow<android.net.Uri?>(null)
    val recoveryLink: StateFlow<android.net.Uri?> = _recoveryLink.asStateFlow()

    /**
     * Spec soft-logout needsPin: the account whose token the server refused and that the hidden password cannot bring
     * back. The app shows recovery-pin for it.
     */
    private val _needsPin = MutableStateFlow<String?>(null)
    val needsPin: StateFlow<String?> = _needsPin.asStateFlow()

    /** Spec soft-logout removed: shown once. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun noticeShown() { _notice.value = null }

    private val signingInAgain = mutableSetOf<String>()
    /** Accounts whose quiet sign-in failed for a reason other than a refused password. */
    private val pendingAuthErrors = mutableSetOf<String>()

    fun showRecovery(show: Boolean) {
        _showsRecovery.value = show
    }

    /** Spec account-start legacyKept: noAccount, and the earlier Bipe app's vault is on this phone (bipe-chat/bipe#603). */
    private val _legacyKept = MutableStateFlow(false)
    val legacyKept: StateFlow<Boolean> = _legacyKept.asStateFlow()

    /** Action startFresh: onboarding-welcome for this launch, although legacyKept. */
    private val _startedFresh = MutableStateFlow(false)
    val startedFresh: StateFlow<Boolean> = _startedFresh.asStateFlow()

    /** Action recoverLegacy: recovery-start, where the earlier app's Block Store token gets the account back. */
    fun recoverLegacy() {
        _showsRecovery.value = true
    }

    /** Action startFresh. The legacy data stays; the next launch checks again. */
    fun startFresh() {
        _startedFresh.value = true
    }

    /** invite-signed-in: an invite code from a link opened while an account is on screen. */
    private val _inviteTap = MutableStateFlow<String?>(null)
    val inviteTap: StateFlow<String?> = _inviteTap.asStateFlow()
    fun inviteTapShown() { _inviteTap.value = null }

    /**
     * Routes a link the app was opened with: recovery links to the recovery flow; an invite on a signed-in phone to
     * invite-signed-in; the rest to onboarding-welcome.
     */
    fun openLink(uri: android.net.Uri) {
        val inviteCode = chat.bipe.shared.BipeInviteLink.code(uri)
        if (chat.bipe.app.recovery.BipeRecoveryLink.token(uri) != null) {
            _recoveryLink.value = uri
            _showsRecovery.value = true
        } else if (inviteCode != null && _phase.value == Phase.READY) {
            _inviteTap.value = inviteCode
        } else {
            pendingInviteLink = uri
        }
    }

    fun consumeRecoveryLink() {
        _recoveryLink.value = null
    }

    /** A recovery path signed in (recovery-pin, recovery-waiting or the beta sign-in): chat-list, then encryption-status. */
    fun didRecover(session: AccountSession) {
        recoveryTokens.syncSoon()
        accountList.merge(listOf(session.userId))
        _showsRecovery.value = false
        show(session)
        accountList.current = session.userId
    }
    private var devAccount: DevAccount? = null
    private var running = false

    /**
     * Action start: open every account on this phone, from its saved session or else its device credential (the hidden
     * password this phone keeps, or a dev test account in a debug build); with no account, finish a pending sign-up
     * (POST /v1/signup/resume, ADR 0094); else noAccount. Recovery is bipe-chat/bipe#556.
     */
    fun start() {
        if (_sessions.value.isNotEmpty() || running) return
        running = true
        _phase.value = Phase.OPENING
        scope.launch {
            try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = Phase.FAILED
            } finally {
                running = false
            }
        }
    }

    /** Action retry: from failed, run start again. */
    fun retry() {
        if (_phase.value == Phase.FAILED) start()
    }

    /**
     * A debug build got a dev test account: sign in with it when the phone has no account yet, or add it as another
     * account on this phone.
     */
    fun useDevAccount(account: DevAccount) {
        devAccount = account
        if (_phase.value == Phase.NO_ACCOUNT || _phase.value == Phase.FAILED) {
            start()
        } else if (_phase.value == Phase.READY && _sessions.value.none { it.userId == account.userId }) {
            devAccount = null
            scope.launch {
                runCatching { AccountSession.signIn(context, account.userId, account.password, sessionStore) }
                    .getOrNull()
                    ?.let { session ->
                        accountList.merge(listOf(session.userId))
                        add(session)
                    }
            }
        }
    }

    /** The account, once start has opened it. A push or a notification action can start the process. */
    suspend fun awaitSession(userId: String, timeoutMs: Long = 20_000): AccountSession? {
        start()
        return withTimeoutOrNull(timeoutMs) {
            sessions.first { list -> list.any { it.userId == userId } }.first { it.userId == userId }
        }
    }

    /** Counts returns to the foreground, so screens can check again (terms-reaccept). */
    private val _foregroundCount = MutableStateFlow(0)
    val foregroundCount: StateFlow<Int> = _foregroundCount.asStateFlow()

    /** Starts the sync again when the app comes back to the foreground. */
    fun resume() {
        _foregroundCount.value += 1
        (context.applicationContext as BipeApplication).push.appBecameActive()
        _sessions.value.forEach { session ->
            scope.launch {
                if (pendingAuthErrors.remove(session.userId)) authError(session, isSoftLogout = true) else session.syncService.start()
            }
            // bipe-chat/bipe#511: this phone's copy of the account's opening hours, for quiet pushes.
            scope.launch { WorkHoursSync.refresh(session, api, (context.applicationContext as BipeApplication).notifications) }
        }
    }

    // soft-logout

    private fun watchAuthErrors(session: AccountSession) {
        session.onAuthError = { isSoftLogout -> scope.launch { authError(session, isSoftLogout) } }
    }

    /** Action authError of spec soft-logout. Runs on the model's scope (main thread). */
    private suspend fun authError(session: AccountSession, isSoftLogout: Boolean) {
        if (_sessions.value.none { it === session } || session.userId in signingInAgain) return
        val name = accountList.name(session.userId) ?: context.getString(chat.bipe.shared.R.string.bipe_tab_you)
        // Spec account-delete notes: the session ended after the due time of a deletion, so the server deleted the
        // account. Remove it from this phone like a sign-out and say so.
        if (DeletionDates.isPast(context, session.userId)) {
            Log.i(AccountStep.TAG, "account deleted on the server")
            removeAccount(session, alreadyLoggedOut = true)
            DeletionDates.remember(context, session.userId, null)
            _notice.value = context.getString(chat.bipe.shared.R.string.bipe_deletion_done)
            return
        }
        if (!isSoftLogout) {
            // Another device signed this phone out: never sign in again by itself (spec soft-logout notes).
            Log.i(AccountStep.TAG, "soft-logout: device signed out on the server")
            removeAccount(session, alreadyLoggedOut = true)
            _notice.value = context.getString(chat.bipe.shared.R.string.bipe_soft_logout_removed, name)
            return
        }
        signingInAgain += session.userId
        try {
            val password = vault.read().accounts[session.userId]?.password
            if (password == null) {
                askForPin(session)
                return
            }
            Log.i(AccountStep.TAG, "soft-logout: signing in again")
            session.pause()
            try {
                replace(session, AccountSession.signInAgain(context, session, password, sessionStore))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AccountSession.isRefused(e)) {
                    askForPin(session)
                } else {
                    // Offline or a local failure: keep everything and try again on the next foreground.
                    Log.w(AccountStep.TAG, "soft-logout: sign-in failed, kept for later")
                    pendingAuthErrors += session.userId
                    session.syncService.start()
                }
            }
        } finally {
            signingInAgain -= session.userId
        }
    }

    /** Spec soft-logout needsPin. Only the refused saved session goes, so recovery-start offers the synced token. */
    private suspend fun askForPin(session: AccountSession) {
        Log.i(AccountStep.TAG, "soft-logout: PIN needed")
        session.pause()
        sessionStore.delete(session.userId)
        _needsPin.value = session.userId
    }

    /** Action later of spec soft-logout: the next foreground or start asks again. */
    fun dismissPin() {
        _needsPin.value?.let { pendingAuthErrors += it }
        _needsPin.value = null
    }

    /** A good PIN brought the account back (recovery-pin submit from needsPin). */
    fun didEnterPin(fresh: AccountSession) {
        _needsPin.value = null
        recoveryTokens.syncSoon()
        val old = _sessions.value.firstOrNull { it.userId == fresh.userId }
        if (old != null) replace(old, fresh) else didRecover(fresh)
    }

    /**
     * Spec data-use clearMediaCache (bipe-chat/bipe#826): stops the account's sync, clears the SDK caches and the app's
     * media folder, then builds the session again from the saved session, as after soft-logout. Keys and the session
     * stay. Throws when the session cannot be built again; the next start restores it.
     */
    fun clearCaches(session: AccountSession, onFailed: () -> Unit) {
        // The app's scope: the settings screen goes away when the session is built again.
        scope.launch {
            try {
                clearCachesNow(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(AccountStep.TAG, "clear caches: session not built again")
                onFailed()
            }
        }
    }

    private suspend fun clearCachesNow(session: AccountSession) {
        if (_sessions.value.none { it === session }) return
        val saved = session.client.session()
        val before = chat.bipe.app.media.StorageMeter.measure(context, session.client)
        session.pause()
        try {
            session.client.clearCaches(session.syncService)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AccountStep.TAG, "clear caches failed, building the session again")
        }
        chat.bipe.app.media.forgetMediaCache()
        chat.bipe.app.media.StorageMeter.deleteAppMedia(context)
        val fresh = AccountSession.restore(context, saved, sessionStore)
        replace(session, fresh)
        // freedBytes: the tabs build again for the new session, so the app's notice says it.
        val after = chat.bipe.app.media.StorageMeter.measure(context, fresh.client)
        val freed = (before.total - after.total).coerceAtLeast(0L)
        _notice.value = context.getString(
            chat.bipe.shared.R.string.bipe_storage_cleared,
            android.text.format.Formatter.formatShortFileSize(context, freed),
        )
    }

    private fun replace(old: AccountSession, fresh: AccountSession) {
        if (_sessions.value.none { it === old }) return
        val app = context.applicationContext as BipeApplication
        app.notifications.detach(old.userId)
        watchAuthErrors(fresh)
        _sessions.value = _sessions.value.map { if (it === old) fresh else it }
        if (_userId.value == fresh.userId) app.push.attach(fresh)
    }

    /** app-tabs switchAccount: makes that account current and saves the choice for the next start. */
    fun switchAccount(userId: String) {
        val session = _sessions.value.firstOrNull { it.userId == userId } ?: return
        _userId.value = userId
        accountList.current = userId
        // Spec location-send notes, end: a switch to another account stops the live location shares.
        chat.bipe.app.location.LiveLocationCenter.accountChanged(userId)
        // Spec push-registration: this phone's pusher follows the current account.
        (context.applicationContext as BipeApplication).push.attach(session)
    }

    /** signup-create made the account and signed in. */
    fun didSignUp(session: AccountSession, outcome: SignupOutcome) {
        recoveryTokens.syncSoon()
        outcome.inviterUserId?.let { InviterGreetings(context, session.userId).set(it, outcome.inviterHandle) }
        // Spec bring-people: the first sheet shows only on the phone that made the account.
        BipeBringPeopleMemory(context, session.userId).markSignedUp()
        accountList.merge(listOf(session.userId))
        _signupOutcome.value = outcome
        show(session)
        accountList.current = session.userId
    }

    /** signup-done finished: chat-list shows, with the inviter's chat open when there is one. */
    fun finishSignup(openRoomId: String?) {
        initialRoomId = openRoomId
        _signupOutcome.value = null
    }

    /**
     * The sign-out of terms-reaccept: the current account leaves this phone. Another account on the phone becomes
     * current; with none left the phone goes back to onboarding.
     */
    fun signOut() {
        session?.let { removeAccount(it, alreadyLoggedOut = false) }
    }

    /** Settings has already confirmed the remote logout. Finish local cleanup once, for this account only. */
    fun completeSignOut(userId: String) {
        _sessions.value.firstOrNull { it.userId == userId }?.let { removeAccount(it, alreadyLoggedOut = true) }
    }

    private fun removeAccount(current: AccountSession, alreadyLoggedOut: Boolean) {
        scope.launch {
            // Detach before any suspending remote cleanup so in-flight actions cannot use this session.
            _sessions.value = _sessions.value.filter { it !== current }
            val app = context.applicationContext as BipeApplication
            app.notifications.detach(current.userId)
            // Spec location-send notes, end: sign-out stops this account's live location shares.
            chat.bipe.app.location.LiveLocationCenter.stopAll(current.userId)
            if (app.roomToOpen.value?.accountUserId == current.userId) app.roomToOpen.value = null
            if (app.accountToOpen.value == current.userId) app.accountToOpen.value = null
            if (app.recoveryToCheck.value == current.userId) app.recoveryToCheck.value = null
            // The pusher still goes before the SDK session ends.
            if (_userId.value == current.userId) app.push.signOut()
            current.signOut(context, sessionStore, vault, alreadyLoggedOut)
            accountList.remove(current.userId)
            if (_userId.value != current.userId) return@launch
            _signupOutcome.value = null
            val next = _sessions.value.firstOrNull()
            if (next != null) {
                switchAccount(next.userId)
            } else {
                _userId.value = null
                _phase.value = Phase.NO_ACCOUNT
            }
        }
    }

    private suspend fun open() {
        // Tokens that did not reach the synced store yet, and tokens from before Block Store was in the app.
        recoveryTokens.syncSoon()
        // The first vault read sets the fork's vault aside; its import waits for the confirmed layout (bipe-chat/bipe#603).
        // A failed import never stops the start: the fork's file stays, and legacyKept offers recovery.
        try {
            vault.read()
            LegacyData.importForkVault(context, vault)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AccountStep.TAG, "earlier app's vault not imported now")
        }
        val (saved, passwords) = AccountStep.run("read saved accounts") {
            sessionStore.loadAll() to vault.read().accounts.filterValues { it.password != null }.mapValues { it.value.password!! }
        }
        val found = passwords.keys.toList() + saved.keys.sorted().filterNot(passwords::containsKey)
        val accounts = accountList.merge(found)

        // The current account first, so the app shows it as soon as it runs; the others open after it.
        val rest = accountList.preferred(accounts).toMutableList()
        var current: AccountSession? = null
        while (current == null && rest.isNotEmpty()) {
            coroutineContext.ensureActive()
            val userId = rest.removeAt(0)
            current = open(userId, saved[userId], passwords[userId])
        }
        if (current != null) {
            show(current)
            rest.forEach { userId -> open(userId, saved[userId], passwords[userId])?.let(::add) }
            return
        }
        if (accounts.isNotEmpty()) {
            _phase.value = Phase.FAILED
            return
        }
        val session = resumePendingSignup() ?: signInWithDevAccount()
        if (session != null) {
            show(session)
        } else {
            // A recovery started on this phone opens recovery-waiting instead of onboarding (spec account-start notes).
            if (vault.read().pendingRecovery != null) _showsRecovery.value = true
            _legacyKept.value = LegacyData.isPresent(context)
            _phase.value = Phase.NO_ACCOUNT
        }
    }

    /** Shows the account without saving it as the choice: an account that failed to open now stays the choice. */
    private fun show(session: AccountSession) {
        add(session)
        _userId.value = session.userId
        // Spec push-registration: keep this phone's pusher current.
        (context.applicationContext as BipeApplication).push.attach(session)
        _phase.value = Phase.READY
    }

    private fun add(session: AccountSession) {
        if (_sessions.value.any { it.userId == session.userId }) return
        val order = accountList.order
        watchAuthErrors(session)
        scope.launch { PhoneReport.report(context, session, api) }
        // Spec location-send notes, end: shares that a closed app left for this account stop now.
        scope.launch { chat.bipe.app.location.LiveLocationCenter.cleanUp(context, session) }
        _sessions.value = (_sessions.value + session).sortedBy { order.indexOf(it.userId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /**
     * Restores the account's saved session; when there is none, or it no longer works, signs in with the hidden
     * password. `null` when neither works now.
     */
    private suspend fun open(userId: String, saved: Session?, password: String?): AccountSession? {
        if (saved != null) {
            try {
                return AccountSession.restore(context, saved, sessionStore)
            } catch (e: AccountStep.TimedOut) {
                // The SDK may still hold the store: retain the session and do not start another login over it.
                Log.w(AccountStep.TAG, "restore timed out; saved session retained")
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountStep.NoStore) {
                // A saved session from an earlier install: drop it and sign in again.
                Log.i(AccountStep.TAG, "saved session without its store, dropped")
                sessionStore.delete(userId)
            } catch (e: Exception) {
                // A transient/local restore error never authorizes deleting a usable session store.
                Log.w(AccountStep.TAG, "restore failed; saved session and store retained")
                return null
            }
        }
        if (password == null) {
            Log.i(AccountStep.TAG, "no device credential for this account")
            return null
        }
        return try {
            AccountSession.signIn(context, userId, password, sessionStore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AccountStep.TAG, "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** A sign-up whose answer never reached this phone (ADR 0094). `null` when there is nothing to finish. */
    private suspend fun resumePendingSignup(): AccountSession? {
        val pending = AccountStep.run("read pending signup") { vault.read().pendingSignup } ?: return null
        val response = try {
            AccountStep.run("resume signup") { api.resumeSignup(pending.key) }
        } catch (error: BipeApiException) {
            if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.NOT_FOUND) {
                AccountStep.run("remove pending signup") { vault.update { it.copy(pendingSignup = null) } }
            }
            return null
        }
        val credentials = response.credentials ?: return null
        AccountStep.run("save signup credential") { vault.store(credentials.userId, credentials.password, credentials.recoveryToken) }
        recoveryTokens.syncSoon()
        val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
        AccountStep.run("remove pending signup") { vault.update { it.copy(pendingSignup = null) } }
        accountList.merge(listOf(session.userId))
        return session
    }

    /** A dev test account of a debug build. The password is used once and never stored. */
    private suspend fun signInWithDevAccount(): AccountSession? {
        val dev = devAccount ?: return null
        val session = AccountSession.signIn(context, dev.userId, dev.password, sessionStore).also { devAccount = null }
        accountList.merge(listOf(session.userId))
        return session
    }
}
