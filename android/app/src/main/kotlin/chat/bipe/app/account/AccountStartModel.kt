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

/** What signup-done needs after a new account (spec signup-done). */
data class SignupOutcome(val inviterUserId: String?, val inviterHandle: String?)

/** Spec account-start (bipe-shared product/screens/account-start.json). */
class AccountStartModel(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { OPENING, NO_ACCOUNT, FAILED, READY }

    /** A dev test account given to a debug build (ADR 0147: Android cannot read the fork's credential). */
    data class DevAccount(val userId: String, val password: String) {
        override fun toString() = "DevAccount($userId)"
    }

    val api = BipeApiClient(AppConfig.API_BASE_URL)
    val vault = BipeAccountVault(context)
    val sessionStore = SessionStore(context)

    private val _phase = MutableStateFlow(Phase.OPENING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    /** Set right after a sign-up: the app shows signup-done before chat-list. */
    private val _signupOutcome = MutableStateFlow<SignupOutcome?>(null)
    val signupOutcome: StateFlow<SignupOutcome?> = _signupOutcome.asStateFlow()

    /** The chat that chat-list opens first, once (signup-done opens the chat with the inviter). */
    var initialRoomId: String? = null
        private set

    var session: AccountSession? = null
        private set

    /** A bipe.chat/convite link the app was opened with, for onboarding-welcome (action openInviteLink). */
    var pendingInviteLink: android.net.Uri? = null
    private var devAccount: DevAccount? = null
    private var running = false

    /**
     * Action start: restore the saved session; else finish a pending sign-up (POST /v1/signup/resume, ADR 0094); else sign
     * in with the device credential (the hidden password this phone keeps, or a dev test account in a debug build);
     * else noAccount. Recovery is bipe-chat/bipe#556.
     */
    fun start() {
        if (session != null || running) return
        running = true
        _phase.value = Phase.OPENING
        scope.launch {
            _phase.value = try {
                open()
            } catch (e: Exception) {
                Phase.FAILED
            } finally {
                running = false
            }
        }
    }

    /** Action retry: from failed, run start again. */
    fun retry() {
        if (_phase.value == Phase.FAILED) start()
    }

    /** A debug build got a dev test account: sign in with it when the phone has no account yet. */
    fun useDevAccount(account: DevAccount) {
        devAccount = account
        if (_phase.value == Phase.NO_ACCOUNT || _phase.value == Phase.FAILED) start()
    }

    /** Counts returns to the foreground, so screens can check again (terms-reaccept). */
    private val _foregroundCount = MutableStateFlow(0)
    val foregroundCount: StateFlow<Int> = _foregroundCount.asStateFlow()

    /** Starts the sync again when the app comes back to the foreground. */
    fun resume() {
        _foregroundCount.value += 1
        val syncService = session?.syncService ?: return
        scope.launch { syncService.start() }
    }

    /** signup-create made the account and signed in. */
    fun didSignUp(session: AccountSession, outcome: SignupOutcome) {
        this.session = session
        _userId.value = session.userId
        outcome.inviterUserId?.let { InviterGreetings(context, session.userId).set(it, outcome.inviterHandle) }
        _signupOutcome.value = outcome
        _phase.value = Phase.READY
    }

    /** signup-done finished: chat-list shows, with the inviter's chat open when there is one. */
    fun finishSignup(openRoomId: String?) {
        initialRoomId = openRoomId
        _signupOutcome.value = null
    }

    /** The sign-out of terms-reaccept: the phone goes back to onboarding. */
    fun signOut() {
        val current = session ?: return
        scope.launch {
            current.signOut(context, sessionStore, vault)
            session = null
            _userId.value = null
            _signupOutcome.value = null
            _phase.value = Phase.NO_ACCOUNT
        }
    }

    private suspend fun open(): Phase {
        val saved = sessionStore.load()
        val newSession = when {
            saved != null -> {
                _userId.value = saved.userId
                AccountSession.restore(context, saved, sessionStore)
            }
            else -> resumePendingSignup() ?: signInWithDeviceCredential() ?: return Phase.NO_ACCOUNT
        }
        session = newSession
        return Phase.READY
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
        _userId.value = credentials.userId
        val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
        vault.update { it.copy(pendingSignup = null) }
        return session
    }

    /** The newest hidden password on this phone, else a dev test account. */
    private suspend fun signInWithDeviceCredential(): AccountSession? {
        val stored = vault.read().accounts.entries.lastOrNull { it.value.password != null }
        if (stored != null) {
            _userId.value = stored.key
            return AccountSession.signIn(context, stored.key, stored.value.password!!, sessionStore)
        }
        val dev = devAccount ?: return null
        _userId.value = dev.userId
        // The password is used once and never stored.
        return AccountSession.signIn(context, dev.userId, dev.password, sessionStore).also { devAccount = null }
    }
}
