// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import chat.bipe.app.common.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client

/** Spec account-start (bipe-shared product/screens/account-start.json). */
class AccountStartModel(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { OPENING, NO_ACCOUNT, FAILED, READY }

    /** A dev test account given to a debug build (ADR 0147: Android cannot read the fork's credential). */
    data class DevAccount(val userId: String, val password: String) {
        override fun toString() = "DevAccount($userId)"
    }

    private val sessionStore = SessionStore(context)
    private val _phase = MutableStateFlow(Phase.OPENING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    var session: AccountSession? = null
        private set
    private var devAccount: DevAccount? = null
    private var running = false

    /**
     * Action start: restore the saved session, else sign in with the device credential, else noAccount.
     * On Android the device credential is a dev test account in a debug build; recovery is bipe-chat/bipe#556,
     * pending sign-up bipe-chat/bipe#554.
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

    /** Starts the sync again when the app comes back to the foreground. */
    fun resume() {
        val syncService = session?.syncService ?: return
        scope.launch { syncService.start() }
    }

    private suspend fun open(): Phase {
        val saved = sessionStore.load()
        val dev = devAccount
        val client: Client
        if (saved != null) {
            _userId.value = saved.userId
            client = AccountSession.makeClient(context, saved.userId, sessionStore)
            client.restoreSession(saved)
        } else if (dev != null) {
            _userId.value = dev.userId
            AccountSession.deleteStore(context, dev.userId)
            client = AccountSession.makeClient(context, dev.userId, sessionStore)
            // MAS compatibility login (m.login.password).
            client.login(dev.userId, dev.password, AppConfig.DEVICE_NAME, null)
            devAccount = null
            sessionStore.save(client.session())
        } else {
            return Phase.NO_ACCOUNT
        }
        val syncService = client.syncService().finish()
        syncService.start()
        session = AccountSession(client, syncService, client.userId())
        return Phase.READY
    }
}
