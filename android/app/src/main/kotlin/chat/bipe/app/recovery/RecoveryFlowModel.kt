// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import android.net.Uri
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SessionStore
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.account.BipePinProblem
import chat.bipe.shared.account.BipeRecoveryTokens
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeCredentials
import chat.bipe.shared.api.BipeRecoveryPurpose
import chat.bipe.shared.api.BipeRecoveryStarted
import chat.bipe.shared.api.BipeRecoveryState
import chat.bipe.shared.generated.BipeOnboardingRecoveryRow
import chat.bipe.shared.generated.BipeRegistries
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Recovery links in the email: `https://bipe.chat/recuperar/#<token>`. The token sits in the fragment, out of server logs. */
object BipeRecoveryLink {
    fun token(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host != "bipe.chat") return null
        if (uri.pathSegments.filter { it.isNotEmpty() } != listOf("recuperar")) return null
        val token = uri.fragment?.trim() ?: return null
        if (token.length !in 1..128 || !token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return token
    }
}

/**
 * The way back into an existing account (ADR 0057, ADR 0059): recovery-start, recovery-pin, recovery-email and
 * recovery-waiting (bipe-shared product/screens). account-start shows it from onboarding-welcome ("Já tenho conta")
 * and while a kept recovery waits. Every path gives access only: message history needs encryption-status.
 */
class RecoveryFlowModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val vault: BipeAccountVault,
    private val recoveryTokens: BipeRecoveryTokens,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    private val onSignedIn: (AccountSession) -> Unit,
) {
    enum class Route { START, PIN, EMAIL, BETA, WAITING }

    /** Spec recovery-pin, state factor. */
    enum class Factor { TOKEN, EMAIL_LINK }

    /** Spec recovery-pin, state phase. NEW_PIN and NEW_PIN_CONFIRM are the signup-pin steps of forgotPin. */
    enum class PinPhase { EDITING, CHECKING, WRONG, BLOCKED, FAILED, NEW_PIN, NEW_PIN_CONFIRM }

    /** Spec recovery-email, state phase. */
    enum class EmailPhase { EDITING, SENDING, SENT, FAILED }

    /** Spec recovery-waiting, state phase. */
    enum class WaitingPhase { WAITING, CLAIMING, READY, CANCELLED, EXPIRED, FAILED }

    private val _route = MutableStateFlow(Route.START)
    val route: StateFlow<Route> = _route.asStateFlow()

    // recovery-start
    private val _rows = MutableStateFlow<List<BipeOnboardingRecoveryRow>>(emptyList())
    val rows: StateFlow<List<BipeOnboardingRecoveryRow>> = _rows.asStateFlow()
    private val _hasSyncedToken = MutableStateFlow(false)
    val hasSyncedToken: StateFlow<Boolean> = _hasSyncedToken.asStateFlow()

    // recovery-pin
    private val _factor = MutableStateFlow(Factor.TOKEN)
    val factor: StateFlow<Factor> = _factor.asStateFlow()
    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()
    private val _pinPhase = MutableStateFlow(PinPhase.EDITING)
    val pinPhase: StateFlow<PinPhase> = _pinPhase.asStateFlow()
    private val _triesLeft = MutableStateFlow<Int?>(null)
    val triesLeft: StateFlow<Int?> = _triesLeft.asStateFlow()
    /** The text of a failed or wrong state (spec state error). */
    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()
    private val _recoveryInvalid = MutableStateFlow(false)
    val recoveryInvalid: StateFlow<Boolean> = _recoveryInvalid.asStateFlow()
    private val _newPinProblem = MutableStateFlow<BipePinProblem?>(null)
    val newPinProblem: StateFlow<BipePinProblem?> = _newPinProblem.asStateFlow()
    private val _newPinMismatch = MutableStateFlow(false)
    val newPinMismatch: StateFlow<Boolean> = _newPinMismatch.asStateFlow()

    // recovery-email
    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()
    private val _emailPhase = MutableStateFlow(EmailPhase.EDITING)
    val emailPhase: StateFlow<EmailPhase> = _emailPhase.asStateFlow()
    private val _emailInvalid = MutableStateFlow(false)
    val emailInvalid: StateFlow<Boolean> = _emailInvalid.asStateFlow()
    private val _emailError = MutableStateFlow<String?>(null)
    val emailError: StateFlow<String?> = _emailError.asStateFlow()

    // beta sign-in (recovery-start action chooseBeta)
    private val _betaEmail = MutableStateFlow("")
    val betaEmail: StateFlow<String> = _betaEmail.asStateFlow()
    private val _betaPassword = MutableStateFlow("")
    val betaPassword: StateFlow<String> = _betaPassword.asStateFlow()
    private val _betaBusy = MutableStateFlow(false)
    val betaBusy: StateFlow<Boolean> = _betaBusy.asStateFlow()
    private val _betaError = MutableStateFlow<String?>(null)
    val betaError: StateFlow<String?> = _betaError.asStateFlow()

    // recovery-waiting
    private val _pending = MutableStateFlow<BipeRecoveryStarted?>(null)
    val pending: StateFlow<BipeRecoveryStarted?> = _pending.asStateFlow()
    private val _waitingPhase = MutableStateFlow(WaitingPhase.WAITING)
    val waitingPhase: StateFlow<WaitingPhase> = _waitingPhase.asStateFlow()

    /** The offered token with its Matrix ID (`null`: the fork's entry from before bipe-chat/bipe#265). */
    private var token: Pair<String?, String>? = null
    private var linkToken: String? = null
    private var newPin = ""
    private var pollJob: Job? = null

    /** Reads the kept recovery: while one waits, recovery-waiting opens first. */
    fun start() {
        scope.launch {
            val kept = vault.read().pendingRecovery
            if (kept != null) {
                _pending.value = kept
                _route.value = Route.WAITING
                poll()
            } else {
                appear()
            }
        }
    }

    /** Back: one screen up. False on recovery-start or while a kept recovery waits. */
    fun back(): Boolean = when (_route.value) {
        Route.START, Route.WAITING -> false
        else -> {
            _route.value = Route.START
            true
        }
    }

    // recovery-start

    /**
     * Action appear: the recovery token of an account not signed in on this phone, from this phone's vault (it stays
     * after a sign-out) or from the synced store (Block Store in the Play build: a new phone finds it, ADR 0059).
     */
    suspend fun appear() {
        // Every account signed in on this phone (ADR 0086): adding an account never recovers one already here.
        val signedIn = sessionStore.loadAll().keys
        token = recoveryTokens.candidate(signedIn)?.let { it.userId to it.token }
        _hasSyncedToken.value = token != null
        _rows.value = BipeOnboardingRecoveryRow.entries.filter { row -> row.gate == null && (!row.needsSyncedToken || token != null) }
    }

    /** Actions chooseToken, chooseEmail and chooseBeta. chooseOldPhone stays hidden until bipe-chat/bipe#203. */
    fun choose(row: BipeOnboardingRecoveryRow) {
        when (row) {
            BipeOnboardingRecoveryRow.TOKEN -> openPin(Factor.TOKEN)
            BipeOnboardingRecoveryRow.EMAIL -> {
                _emailPhase.value = EmailPhase.EDITING
                _emailError.value = null
                _route.value = Route.EMAIL
            }
            BipeOnboardingRecoveryRow.BETA -> {
                _betaError.value = null
                _route.value = Route.BETA
            }
            BipeOnboardingRecoveryRow.OLD_PHONE -> Unit
        }
    }

    // recovery-pin

    private fun openPin(factor: Factor) {
        _factor.value = factor
        _pin.value = ""
        _pinPhase.value = PinPhase.EDITING
        _triesLeft.value = null
        _pinError.value = null
        _recoveryInvalid.value = false
        _newPinProblem.value = null
        _newPinMismatch.value = false
        _route.value = Route.PIN
    }

    /** Actions typeDigit and deleteDigit, through the system digit keyboard. At pinLength digits, submits. */
    fun editPin(text: String) {
        val phase = _pinPhase.value
        if (phase == PinPhase.CHECKING || phase == PinPhase.BLOCKED) return
        val digits = BipePin.sanitize(text)
        _pin.value = digits
        if (phase == PinPhase.WRONG || phase == PinPhase.FAILED) {
            _pinPhase.value = PinPhase.EDITING
            _pinError.value = null
        }
        _newPinProblem.value = null
        _newPinMismatch.value = false
        if (digits.length != BipeRegistries.PIN_LENGTH) return
        when (phase) {
            PinPhase.NEW_PIN -> {
                val problem = BipePin.problem(digits)
                if (problem != null) {
                    _newPinProblem.value = problem
                } else {
                    newPin = digits
                    _pinPhase.value = PinPhase.NEW_PIN_CONFIRM
                }
                _pin.value = ""
            }
            PinPhase.NEW_PIN_CONFIRM -> {
                if (digits == newPin) {
                    submitNewPin()
                } else {
                    _newPinMismatch.value = true
                    newPin = ""
                    _pin.value = ""
                    _pinPhase.value = PinPhase.NEW_PIN
                }
            }
            else -> submit()
        }
    }

    /** Action submit. */
    fun submit() {
        val entered = _pin.value
        if (entered.length != BipeRegistries.PIN_LENGTH) return
        _pinPhase.value = PinPhase.CHECKING
        scope.launch {
            try {
                when (_factor.value) {
                    Factor.TOKEN -> {
                        val (owner, secret) = token ?: throw BipeApiException.Server(BipeApiErrorCode.RECOVERY_INVALID, null, 403)
                        val credentials = api.recoverWithToken(secret, entered)
                        _pin.value = ""
                        // The account's new token goes under its own key; the entry without an owner is done.
                        if (owner == null && credentials.recoveryToken != secret) recoveryTokens.revoke(null, secret)
                        signIn(credentials)
                    }
                    Factor.EMAIL_LINK -> {
                        val link = linkToken ?: throw BipeApiException.Server(BipeApiErrorCode.RECOVERY_INVALID, null, 403)
                        val started = api.confirmRecoveryEmail(link, entered, null)
                        _pin.value = ""
                        startWaiting(started)
                    }
                }
            } catch (error: BipeApiException) {
                _pin.value = ""
                handlePinError(error)
            } catch (error: Exception) {
                _pin.value = ""
                _pinPhase.value = PinPhase.FAILED
                _pinError.value = context.getString(R.string.bipe_error_generic)
            }
        }
    }

    /**
     * Action forgotPin: emailLink asks for a new PIN (a pin_reset recovery); token opens recovery-email, because the PIN
     * resets only through the recovery e-mail.
     */
    fun forgotPin() {
        when (_factor.value) {
            Factor.EMAIL_LINK -> {
                _pin.value = ""
                newPin = ""
                _pinError.value = null
                _pinPhase.value = PinPhase.NEW_PIN
            }
            Factor.TOKEN -> choose(BipeOnboardingRecoveryRow.EMAIL)
        }
    }

    private fun submitNewPin() {
        val link = linkToken ?: return
        _pinPhase.value = PinPhase.CHECKING
        scope.launch {
            try {
                val started = api.confirmRecoveryEmail(link, null, newPin)
                newPin = ""
                _pin.value = ""
                startWaiting(started)
            } catch (error: BipeApiException) {
                _pin.value = ""
                val server = error as? BipeApiException.Server
                if (server?.code == BipeApiErrorCode.PIN_WEAK) {
                    _newPinProblem.value = server.reason?.let(BipePinProblem::fromId) ?: BipePinProblem.SEQUENCE
                    newPin = ""
                    _pinPhase.value = PinPhase.NEW_PIN
                } else {
                    handlePinError(error)
                }
            }
        }
    }

    private suspend fun handlePinError(error: BipeApiException) {
        val server = error as? BipeApiException.Server
        when (server?.code) {
            BipeApiErrorCode.PIN_WRONG -> {
                _triesLeft.value = server?.triesLeft
                _pinPhase.value = PinPhase.WRONG
                _pinError.value = error.localizedMessage(context)
            }
            BipeApiErrorCode.PIN_BLOCKED -> {
                _pinPhase.value = PinPhase.BLOCKED
                _pinError.value = null
            }
            BipeApiErrorCode.RECOVERY_INVALID -> {
                _pinPhase.value = PinPhase.FAILED
                _pinError.value = error.localizedMessage(context)
                _recoveryInvalid.value = true
                // Spec notes: an old or revoked token is deleted; recovery-start shows without the token row.
                val current = token
                if (_factor.value == Factor.TOKEN && current != null) {
                    recoveryTokens.revoke(current.first, current.second)
                    appear()
                }
            }
            else -> {
                _pinPhase.value = PinPhase.FAILED
                _pinError.value = error.localizedMessage(context)
            }
        }
    }

    /** From recovery_invalid: back to recovery-start. */
    fun backToStart() {
        _route.value = Route.START
        scope.launch { appear() }
    }

    // recovery-email

    /** Action editEmail. */
    fun editEmail(text: String) {
        _email.value = text
        _emailInvalid.value = false
        if (_emailPhase.value == EmailPhase.FAILED) {
            _emailPhase.value = EmailPhase.EDITING
            _emailError.value = null
        }
    }

    /** Action send: checks the address locally, then POST /v1/recovery/email. */
    fun sendEmail() {
        val address = _email.value.trim()
        if (!isPlausibleEmail(address)) {
            _emailInvalid.value = true
            return
        }
        _emailPhase.value = EmailPhase.SENDING
        scope.launch {
            try {
                api.requestRecoveryEmail(address)
                _emailPhase.value = EmailPhase.SENT
            } catch (error: BipeApiException) {
                _emailPhase.value = EmailPhase.FAILED
                val code = (error as? BipeApiException.Server)?.code
                _emailError.value = context.getString(
                    when (code) {
                        BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        BipeApiErrorCode.EMAIL_UNAVAILABLE -> R.string.bipe_error_email_unavailable
                        else -> R.string.bipe_error_network
                    },
                )
            }
        }
    }

    /** Action openLink: the recovery link from the e-mail, opened on this phone. A link without a token does nothing. */
    fun openLink(uri: Uri) {
        val link = BipeRecoveryLink.token(uri) ?: return
        if (_pending.value != null) return
        linkToken = link
        openPin(Factor.EMAIL_LINK)
    }

    private fun isPlausibleEmail(address: String): Boolean {
        val parts = address.split("@")
        if (parts.size != 2 || parts[0].isEmpty() || ' ' in address) return false
        val domain = parts[1]
        return '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
    }

    // beta sign-in

    fun editBetaEmail(text: String) {
        _betaEmail.value = text
        _betaError.value = null
    }

    fun editBetaPassword(text: String) {
        _betaPassword.value = text
        _betaError.value = null
    }

    fun canSubmitBeta(email: String, password: String, busy: Boolean): Boolean = !busy && email.isNotBlank() && password.isNotEmpty()

    /**
     * Action chooseBeta: Client.login with the e-mail as user and the password, then the move to device-first
     * (POST /v1/me/device-first). The password is never stored; the move gives the hidden password and the token.
     */
    fun submitBeta() {
        if (!canSubmitBeta(_betaEmail.value, _betaPassword.value, _betaBusy.value)) return
        _betaBusy.value = true
        val user = _betaEmail.value.trim()
        val password = _betaPassword.value
        scope.launch {
            try {
                val session = AccountSession.signIn(context, user, password, sessionStore)
                _betaPassword.value = ""
                runCatching {
                    val credentials = api.moveToDeviceFirst(session.client.session().accessToken)
                    vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
                }
                onSignedIn(session)
            } catch (error: Exception) {
                _betaError.value = context.getString(R.string.bipe_error_generic)
            } finally {
                _betaBusy.value = false
            }
        }
    }

    // recovery-waiting

    private suspend fun startWaiting(started: BipeRecoveryStarted) {
        vault.savePendingRecovery(started)
        _pending.value = started
        _waitingPhase.value = WaitingPhase.WAITING
        _route.value = Route.WAITING
        poll()
    }

    /** Action claim, on open, on foreground and at readyAt. Before readyAt the server answers waiting. */
    fun pollNow() {
        scope.launch { poll() }
    }

    private suspend fun poll() {
        val kept = _pending.value ?: return
        if (_waitingPhase.value != WaitingPhase.WAITING && _waitingPhase.value != WaitingPhase.FAILED) return
        _waitingPhase.value = WaitingPhase.CLAIMING
        try {
            val claim = api.claimRecovery(kept.recoveryId, kept.claimSecret)
            when (claim.state) {
                BipeRecoveryState.WAITING -> {
                    _waitingPhase.value = WaitingPhase.WAITING
                    schedulePoll(claim.readyAt ?: kept.readyAt)
                }
                BipeRecoveryState.COMPLETED -> {
                    val credentials = claim.credentials
                    if (kept.purpose == BipeRecoveryPurpose.ACCESS && credentials != null) {
                        signIn(credentials)
                    }
                    vault.deletePendingRecovery()
                    _waitingPhase.value = WaitingPhase.READY
                }
                BipeRecoveryState.CANCELLED -> _waitingPhase.value = WaitingPhase.CANCELLED
                BipeRecoveryState.EXPIRED -> _waitingPhase.value = WaitingPhase.EXPIRED
            }
        } catch (error: BipeApiException) {
            _waitingPhase.value =
                if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.RECOVERY_INVALID) WaitingPhase.EXPIRED else WaitingPhase.FAILED
        } catch (error: Exception) {
            _waitingPhase.value = WaitingPhase.FAILED
        }
    }

    /** Action startOver: from cancelled or expired (and after a PIN reset), back to recovery-start. */
    fun startOver() {
        pollJob?.cancel()
        _pending.value = null
        _waitingPhase.value = WaitingPhase.WAITING
        _route.value = Route.START
        scope.launch {
            vault.deletePendingRecovery()
            appear()
        }
    }

    private fun schedulePoll(at: Instant) {
        pollJob?.cancel()
        val wait = (at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) + 2_000
        pollJob = scope.launch {
            delay(wait)
            poll()
        }
    }

    fun close() {
        pollJob?.cancel()
    }

    // Sign-in

    /** Stores the password (this phone only) and the token, signs in, then opens chat-list and encryption-status. */
    private suspend fun signIn(credentials: BipeCredentials) {
        vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
        val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
        onSignedIn(session)
    }
}
