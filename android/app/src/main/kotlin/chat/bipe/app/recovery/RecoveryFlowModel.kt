// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import android.net.Uri
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SessionStore
import chat.bipe.shared.BipeRecoveryLink
import chat.bipe.shared.BipeSignupRules
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.account.BipePendingClaim
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeOnboardingRecoveryRow
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The way back into an existing account: recovery-start, recovery-pin, recovery-email and the beta sign-in
 * (bipe-shared product/screens). State and action names follow the specs, as on iOS.
 */
class RecoveryFlowModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val vault: BipeAccountVault,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    private val onSignedIn: (AccountSession) -> Unit,
    private val onWaiting: () -> Unit,
) {
    enum class Route { PIN, EMAIL, NEW_PIN, NEW_PIN_CONFIRM, BETA }

    private val _path = MutableStateFlow<List<Route>>(emptyList())
    val path: StateFlow<List<Route>> = _path.asStateFlow()

    /** System back. `false` on recovery-start. */
    fun back(): Boolean {
        if (_path.value.isEmpty()) return false
        _path.value = _path.value.dropLast(1)
        return true
    }

    // recovery-start

    val hasSyncedToken = MutableStateFlow(false)
    private var token: Pair<String, String>? = null

    val rows: List<BipeOnboardingRecoveryRow>
        get() = BipeOnboardingRecoveryRow.entries.filter { row ->
            // A gated row stays hidden until its issue ships (oldPhone: bipe-chat/bipe#203).
            row.gate == null && (!row.needsSyncedToken || hasSyncedToken.value)
        }

    // recovery-pin

    sealed interface Factor {
        data object Token : Factor
        data class EmailLink(val token: String) : Factor {
            override fun toString(): String = "EmailLink"
        }
    }

    enum class PinPhase { EDITING, CHECKING, WRONG, BLOCKED, FAILED }

    val factor = MutableStateFlow<Factor>(Factor.Token)
    val pin = MutableStateFlow("")
    val pinPhase = MutableStateFlow(PinPhase.EDITING)
    val triesLeft = MutableStateFlow(0)
    val pinError = MutableStateFlow<String?>(null)
    val newPin = MutableStateFlow("")
    private var firstNewPin = ""
    val newPinProblem = MutableStateFlow<String?>(null)

    // recovery-email

    enum class EmailPhase { EDITING, SENDING, SENT, FAILED }

    val email = MutableStateFlow("")
    val emailPhase = MutableStateFlow(EmailPhase.EDITING)
    val emailInvalid = MutableStateFlow(false)
    val emailError = MutableStateFlow<String?>(null)

    // beta sign-in

    val betaEmail = MutableStateFlow("")
    val betaPassword = MutableStateFlow("")
    val betaBusy = MutableStateFlow(false)
    val betaError = MutableStateFlow<String?>(null)

    /**
     * Action appear of recovery-start: a recovery token of an account that is not signed in on this phone. Android keeps
     * tokens on this phone only until Block Store is decided (bipe-chat/bipe#578).
     */
    fun appear() {
        scope.launch {
            val signedIn = sessionStore.load()?.userId
            token = vault.read().accounts.entries.lastOrNull { it.key != signedIn && it.value.token != null }?.let { it.key to it.value.token!! }
            hasSyncedToken.value = token != null
        }
    }

    /** Action chooseToken. */
    fun chooseToken() {
        factor.value = Factor.Token
        resetPin()
        _path.value = listOf(Route.PIN)
    }

    /** Action chooseEmail. */
    fun chooseEmail() {
        _path.value = listOf(Route.EMAIL)
    }

    /** Action chooseBeta. */
    fun chooseBeta() {
        _path.value = listOf(Route.BETA)
    }

    /** Action openLink of recovery-email: the link from the recovery e-mail, opened on this phone. */
    fun openLink(url: Uri) {
        val linkToken = BipeRecoveryLink.token(url) ?: return
        factor.value = Factor.EmailLink(linkToken)
        resetPin()
        _path.value = listOf(Route.PIN)
    }

    // recovery-pin actions

    /** Actions typeDigit and deleteDigit. At pinLength digits, submits. */
    fun editPin(text: String) {
        pin.value = BipePin.sanitize(text)
        if (pinPhase.value == PinPhase.WRONG || pinPhase.value == PinPhase.FAILED) pinPhase.value = PinPhase.EDITING
        if (pin.value.length == BipeRegistries.PIN_LENGTH) submit(pin.value, null)
    }

    /** Action forgotPin: the e-mail path sets a new PIN; the token path goes to the recovery e-mail. */
    fun forgotPin() {
        when (factor.value) {
            is Factor.EmailLink -> {
                newPin.value = ""
                firstNewPin = ""
                newPinProblem.value = null
                _path.value = _path.value + Route.NEW_PIN
            }
            Factor.Token -> _path.value = listOf(Route.EMAIL)
        }
    }

    /** The new PIN of a PIN reset, checked like signup-pin. */
    fun editNewPin(text: String, confirm: Boolean) {
        newPin.value = BipePin.sanitize(text)
        newPinProblem.value = null
        if (newPin.value.length != BipeRegistries.PIN_LENGTH) return
        if (!confirm) {
            val problem = BipePin.problem(newPin.value)
            if (problem != null) {
                newPinProblem.value = problem.localizedMessage(context)
                newPin.value = ""
            } else {
                firstNewPin = newPin.value
                newPin.value = ""
                _path.value = _path.value + Route.NEW_PIN_CONFIRM
            }
        } else if (newPin.value == firstNewPin) {
            val chosen = newPin.value
            newPin.value = ""
            submit(null, chosen)
        } else {
            newPinProblem.value = context.getString(R.string.bipe_pin_mismatch)
            newPin.value = ""
            firstNewPin = ""
            _path.value = _path.value - Route.NEW_PIN_CONFIRM
        }
    }

    private fun submit(pinValue: String?, newPinValue: String?) {
        pinPhase.value = PinPhase.CHECKING
        pinError.value = null
        scope.launch {
            when (val current = factor.value) {
                Factor.Token -> {
                    val (tokenUserId, tokenValue) = token ?: return@launch
                    try {
                        val credentials = api.recoverWithToken(tokenValue, pinValue ?: return@launch)
                        vault.update { data ->
                            // The server replaced the token. A token kept under another key is removed.
                            val others = if (tokenUserId != credentials.userId) data.accounts - tokenUserId else data.accounts
                            data.copy(accounts = others)
                        }
                        vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
                        onSignedIn(AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore))
                    } catch (error: BipeApiException) {
                        if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.RECOVERY_INVALID) {
                            // Old or revoked: delete it and go back to recovery-start without the token row.
                            vault.update { data ->
                                data.copy(accounts = data.accounts + (tokenUserId to (data.accounts[tokenUserId]?.copy(token = null) ?: return@update data)))
                            }
                            appear()
                            _path.value = emptyList()
                        }
                        failPin(error)
                    } catch (error: Exception) {
                        pinPhase.value = PinPhase.FAILED
                        pinError.value = context.getString(R.string.bipe_error_generic)
                    }
                }
                is Factor.EmailLink -> try {
                    val started = api.confirmEmailRecovery(current.token, pinValue, newPinValue)
                    vault.update {
                        it.copy(claim = BipePendingClaim(started.recoveryId, started.claimSecret, started.purposeId, started.readyAt.toEpochMilli()))
                    }
                    onWaiting()
                } catch (error: BipeApiException) {
                    failPin(error)
                }
            }
            pin.value = ""
        }
    }

    private fun failPin(error: BipeApiException) {
        val server = error as? BipeApiException.Server
        when {
            server?.code == BipeApiErrorCode.PIN_WRONG -> {
                pinPhase.value = PinPhase.WRONG
                triesLeft.value = server?.triesLeft ?: 0
            }
            server?.code == BipeApiErrorCode.PIN_BLOCKED -> pinPhase.value = PinPhase.BLOCKED
            else -> {
                pinPhase.value = PinPhase.FAILED
                pinError.value = context.getString(
                    when {
                        error is BipeApiException.Network -> R.string.bipe_error_network
                        server?.code == BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        server?.code == BipeApiErrorCode.RECOVERY_INVALID -> R.string.bipe_error_recovery_invalid
                        else -> R.string.bipe_error_generic
                    },
                )
            }
        }
    }

    private fun resetPin() {
        pin.value = ""
        pinPhase.value = PinPhase.EDITING
        pinError.value = null
    }

    // recovery-email actions

    /** Action editEmail. */
    fun editEmail(text: String) {
        email.value = text
        emailInvalid.value = false
    }

    /** Action send. The answer is 202 also for an unknown address. */
    fun send() {
        val address = email.value.trim()
        if (!BipeSignupRules.isPlausibleEmail(address)) {
            emailInvalid.value = true
            return
        }
        emailPhase.value = EmailPhase.SENDING
        emailError.value = null
        scope.launch {
            try {
                api.requestEmailRecovery(address)
                emailPhase.value = EmailPhase.SENT
            } catch (error: BipeApiException) {
                emailPhase.value = EmailPhase.FAILED
                val code = (error as? BipeApiException.Server)?.code
                emailError.value = context.getString(
                    when (code) {
                        BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        BipeApiErrorCode.EMAIL_UNAVAILABLE -> R.string.bipe_error_email_unavailable
                        else -> R.string.bipe_error_network
                    },
                )
            }
        }
    }

    // beta sign-in (recovery-start, action chooseBeta)

    fun editBetaEmail(text: String) {
        betaEmail.value = text
        betaError.value = null
    }

    fun editBetaPassword(text: String) {
        betaPassword.value = text
        betaError.value = null
    }

    /** Client.login with the e-mail as user, then POST /v1/me/device-first. Without a PIN the account stays a beta account. */
    fun signInBeta() {
        val user = betaEmail.value.trim()
        val password = betaPassword.value
        if (user.isEmpty() || password.isEmpty()) return
        betaBusy.value = true
        scope.launch {
            try {
                val session = AccountSession.signIn(context, user, password, sessionStore)
                betaPassword.value = ""
                session.accessToken()?.let { accessToken ->
                    runCatching { api.moveToDeviceFirst(accessToken) }.getOrNull()?.let {
                        vault.store(it.userId, it.password, it.recoveryToken)
                    }
                }
                onSignedIn(session)
            } catch (error: Exception) {
                betaError.value = context.getString(R.string.bipe_error_generic)
            } finally {
                betaBusy.value = false
            }
        }
    }
}
