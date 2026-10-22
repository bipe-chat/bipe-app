// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import chat.bipe.app.common.AppConfig
import chat.bipe.app.common.InviterGreetings
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import kotlinx.coroutines.CoroutineScope
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
    enum class Phase { OPENING, NO_ACCOUNT, FAILED, READY }

    /** A dev test account given to a debug build (ADR 0147: Android cannot read the fork's credential). */
    data class DevAccount(val userId: String, val password: String) {
        override fun toString() = "DevAccount($userId)"
    }

    val api = BipeApiClient(AppConfig.API_BASE_URL)
    val vault = BipeAccountVault(context)
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

    /** Counts returns to the foreground, so screens can check again (terms-reaccept). */
    private val _foregroundCount = MutableStateFlow(0)
    val foregroundCount: StateFlow<Int> = _foregroundCount.asStateFlow()

    /** Starts the sync again when the app comes back to the foreground. */
    fun resume() {
        _foregroundCount.value += 1
        _sessions.value.forEach { session -> scope.launch { session.syncService.start() } }
    }

    /** app-tabs switchAccount: makes that account current and saves the choice for the next start. */
    fun switchAccount(userId: String) {
        if (_sessions.value.none { it.userId == userId }) return
        _userId.value = userId
        accountList.current = userId
    }

    /** signup-create made the account and signed in. */
    fun didSignUp(session: AccountSession, outcome: SignupOutcome) {
        outcome.inviterUserId?.let { InviterGreetings(context, session.userId).set(it, outcome.inviterHandle) }
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
        val current = session ?: return
        scope.launch {
            current.signOut(context, sessionStore, vault)
            accountList.remove(current.userId)
            _sessions.value = _sessions.value.filter { it.userId != current.userId }
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
        val saved = sessionStore.loadAll()
        val passwords = vault.read().accounts.filterValues { it.password != null }.mapValues { it.value.password!! }
        val found = passwords.keys.toList() + saved.keys.sorted().filterNot(passwords::containsKey)
        val accounts = accountList.merge(found)

        // The current account first, so the app shows it as soon as it runs; the others open after it.
        val rest = accountList.preferred(accounts).toMutableList()
        var current: AccountSession? = null
        while (current == null && rest.isNotEmpty()) {
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
        if (session != null) show(session) else _phase.value = Phase.NO_ACCOUNT
    }

    /** Shows the account without saving it as the choice: an account that failed to open now stays the choice. */
    private fun show(session: AccountSession) {
        add(session)
        _userId.value = session.userId
        _phase.value = Phase.READY
    }

    private fun add(session: AccountSession) {
        if (_sessions.value.any { it.userId == session.userId }) return
        val order = accountList.order
        _sessions.value = (_sessions.value + session).sortedBy { order.indexOf(it.userId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /**
     * Restores the account's saved session; when there is none, or it no longer works, signs in with the hidden
     * password. `null` when neither works now.
     */
    private suspend fun open(userId: String, saved: Session?, password: String?): AccountSession? {
        if (saved != null) {
            runCatching { AccountSession.restore(context, saved, sessionStore) }.getOrNull()?.let { return it }
        }
        if (password == null) return null
        return runCatching { AccountSession.signIn(context, userId, password, sessionStore) }.getOrNull()
    }

    /** A sign-up whose answer never reached this phone (ADR 0094). `null` when there is nothing to finish. */
    private suspend fun resumePendingSignup(): AccountSession? {
        val pending = vault.read().pendingSignup ?: return null
        val response = try {
            api.resumeSignup(pending.key)
        } catch (error: BipeApiException) {
            if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.NOT_FOUND) {
                vault.update { it.copy(pendingSignup = null) }
            }
            return null
        }
        val credentials = response.credentials ?: return null
        vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
        val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
        vault.update { it.copy(pendingSignup = null) }
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
