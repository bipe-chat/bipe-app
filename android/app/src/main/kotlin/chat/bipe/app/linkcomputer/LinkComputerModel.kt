// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.linkcomputer

import android.content.Context
import android.util.Base64
import chat.bipe.app.settings.SettingsModel
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.settings.checkPin
import chat.bipe.shared.weblink.BipeLinkComputer
import chat.bipe.shared.weblink.BipeMatrixAccountApi
import chat.bipe.shared.weblink.BipeWebLink
import chat.bipe.shared.weblink.finishWebLink
import chat.bipe.shared.weblink.webLinkPlace
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.CheckCodeSender
import org.matrix.rustcomponents.sdk.ContinuationMessageSender
import org.matrix.rustcomponents.sdk.GrantGeneratedQrLoginProgress
import org.matrix.rustcomponents.sdk.GrantGeneratedQrLoginProgressListener
import org.matrix.rustcomponents.sdk.GrantQrLoginProgress
import org.matrix.rustcomponents.sdk.GrantQrLoginProgressListener
import org.matrix.rustcomponents.sdk.HumanQrGrantLoginException
import org.matrix.rustcomponents.sdk.QrCodeData
import uniffi.matrix_sdk_crypto.QrCodeIntent

/**
 * Specs link-computer-scan, link-computer-warning and link-computer-approve (bipe-shared product/screens): the phone
 * scans the QR code of chat.bipe.net.br, opens the MSC4108 secure channel, warns, waits through the cooldown, checks
 * the owner and approves on the MAS consent page in a private web view (ADR 0116, ADR 0118).
 *
 * Mode PHONE is specs link-phone-show and link-phone-approve: this old phone shows the code, the new phone scans it
 * (ADR 0059), and the same warning, owner check and MAS page approve it.
 */
class LinkComputerModel(
    private val context: Context,
    private val settings: SettingsModel,
    private val scope: CoroutineScope,
    val mode: Mode = Mode.COMPUTER,
) {
    private val session get() = settings.session
    private val matrix get() = BipeMatrixAccountApi(session.client.homeserver())

    /** The hidden MAS password of the account on this phone (ADR 0057). Read only after the owner check. */
    private suspend fun hiddenPassword(userId: String): String? =
        runCatching { BipeAccountVault(context).read().accounts[userId]?.password }.getOrNull()

    /** Type LinkComputerSupport. */
    enum class Support { CHECKING, SUPPORTED, NEEDS_PIN, NOT_SUPPORTED }
    enum class Mode { COMPUTER, PHONE }

    /** SHOW is link-phone-show (mode PHONE). */
    enum class Step { SCAN, SHOW, WARNING, APPROVE }
    enum class ScanPhase { CAMERA_DENIED, SCANNING, CONNECTING, SHOW_CHECK_CODE, FAILED }

    /** Spec link-phone-show, state phase. */
    enum class ShowPhase { PREPARING, SHOWING_CODE, ENTER_CHECK_CODE, CHECKING, FAILED }
    enum class ApprovePhase { CONFIRM_IDENTITY, LOADING, CONSENT, FINISHING, DONE, FAILED }

    /** The failed phases, each with its string per mode. */
    enum class Failure(private val computer: Int, private val phone: Int = computer) {
        NOT_BIPE_CODE(R.string.bipe_app_link_computer_not_bipe_code),
        EXPIRED(R.string.bipe_app_link_computer_expired, R.string.bipe_app_link_phone_expired),
        INSECURE(R.string.bipe_app_link_computer_insecure, R.string.bipe_app_link_phone_insecure),
        NEEDS_RECOVERY_KEY(R.string.bipe_app_link_computer_needs_recovery_key, R.string.bipe_app_link_phone_needs_recovery_key),
        WRONG_CHECK_CODE(R.string.bipe_app_link_phone_wrong_check_code),
        STOPPED(R.string.bipe_app_link_computer_stopped),
        NETWORK(R.string.bipe_error_network),
        SIGN_IN(R.string.bipe_link_computer_error_sign_in),
        GENERIC(R.string.bipe_error_generic),
        ;

        fun message(mode: Mode): Int = if (mode == Mode.COMPUTER) computer else phone
    }

    data class State(
        val support: Support = Support.CHECKING,
        val step: Step = Step.SCAN,
        val scanPhase: ScanPhase = ScanPhase.SCANNING,
        val checkCode: String? = null,
        val scanError: Failure? = null,
        val showPhase: ShowPhase = ShowPhase.PREPARING,
        /** QrCodeData.toBytes of qrReady, as base64 (RFC 4648, with padding): the copy text and the QR content. */
        val qrCode: String? = null,
        val typedCheckCode: String = "",
        val showError: Failure? = null,
        /** When the new phone scanned the code (link-phone-approve requestedAt). */
        val requestedAt: Instant? = null,
        val secondsLeft: Int = BipeRegistries.WEB_LINK_COOLDOWN_SECONDS,
        val approvePhase: ApprovePhase = ApprovePhase.CONFIRM_IDENTITY,
        val place: String? = null,
        val placeKnown: Boolean = false,
        val approveError: Failure? = null,
        /** The page the web view opens; set once the MAS host is known. */
        val consentUrl: String? = null,
        val masHost: String? = null,
        val isClosed: Boolean = false,
    ) {
        val canContinue: Boolean get() = secondsLeft == 0
    }

    private val _state = MutableStateFlow(State(step = if (mode == Mode.COMPUTER) Step.SCAN else Step.SHOW))
    val state: StateFlow<State> = _state.asStateFlow()

    private var verificationUri: String? = null
    private var continuationSender: ContinuationMessageSender? = null
    private var checkCodeSender: CheckCodeSender? = null
    /** Mode PHONE: each code has its own number, so that a dropped code never touches the next one. */
    private var codeAttempt = 0
    private var grantJob: Job? = null
    private var countdown: Job? = null
    private var startedAt: Instant = Instant.now()
    private var filledLoginForm = false
    private var sawConsentPage = false
    private var finished = false
    /** Held only between the owner check and the one submit of the MAS login form. */
    private var password: String? = null

    /** Action open: the Bipe support gate (never Client.isLoginWithQrCodeSupported, ADR 0118). */
    fun open(cameraAllowed: Boolean) {
        scope.launch {
            val serverSupports = matrix.supportsQrLogin()
            val hasPassword = hiddenPassword(session.userId) != null
            val support = when {
                serverSupports && hasPassword -> Support.SUPPORTED
                // A beta account without a hidden password on this phone needs the PIN first (ADR 0057).
                serverSupports -> Support.NEEDS_PIN
                else -> Support.NOT_SUPPORTED
            }
            _state.update { it.copy(support = support, scanPhase = if (cameraAllowed) ScanPhase.SCANNING else ScanPhase.CAMERA_DENIED) }
            if (support == Support.SUPPORTED && mode == Mode.PHONE) generate()
        }
    }

    fun cameraResult(allowed: Boolean) {
        _state.update { it.copy(scanPhase = if (allowed) ScanPhase.SCANNING else ScanPhase.CAMERA_DENIED) }
    }

    /** Action codeRead: only a login code from a new device starts the grant. */
    fun codeRead(bytes: ByteArray) {
        val current = _state.value
        if (grantJob != null || (current.scanPhase != ScanPhase.SCANNING && current.scanPhase != ScanPhase.FAILED)) return
        val data = runCatching { QrCodeData.fromBytes(bytes) }.getOrNull()
        // Only a login code whose rendezvous is on this account's homeserver (bipe-chat/bipe#785, ADR 0116).
        if (data == null || data.intent() != QrCodeIntent.LOGIN ||
            !BipeWebLink.isOwnRendezvous(bytes, data.baseUrl(), session.client.homeserver())
        ) {
            _state.update { it.copy(scanError = Failure.NOT_BIPE_CODE, scanPhase = ScanPhase.FAILED) }
            return
        }
        _state.update { it.copy(scanError = null, scanPhase = ScanPhase.CONNECTING) }
        startedAt = Instant.now()
        val handler = session.client.newGrantLoginWithQrCodeHandler()
        val listener = object : GrantQrLoginProgressListener {
            override fun onUpdate(state: GrantQrLoginProgress) {
                scope.launch { progress(state) }
            }
        }
        grantJob = scope.launch {
            try {
                // Off the main thread: the TLS check fetches CRLs, and Android forbids network on main.
                withContext(Dispatchers.IO) { handler.scan(data, listener) }
                progress(GrantQrLoginProgress.Done)
            } catch (e: CancellationException) {
                // Scan again or cancel dropped the task.
                throw e
            } catch (e: Exception) {
                taskFailed(e)
            }
        }
    }

    /** Action open (mode PHONE): GrantLoginWithQrCodeHandler.generate; the code arrives with qrReady. */
    private fun generate() {
        if (grantJob != null) return
        _state.update { it.copy(showError = null, showPhase = ShowPhase.PREPARING) }
        startedAt = Instant.now()
        codeAttempt += 1
        val current = codeAttempt
        val handler = session.client.newGrantLoginWithQrCodeHandler()
        val listener = object : GrantGeneratedQrLoginProgressListener {
            override fun onUpdate(state: GrantGeneratedQrLoginProgress) {
                scope.launch { if (codeAttempt == current) generatedProgress(state) }
            }
        }
        grantJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { handler.generate(listener) }
                if (codeAttempt == current) generatedProgress(GrantGeneratedQrLoginProgress.Done)
            } catch (e: CancellationException) {
                // A new code or cancel dropped the task.
                throw e
            } catch (e: Exception) {
                if (codeAttempt == current) taskFailed(e)
            }
        }
    }

    /** Action progress (mode PHONE). */
    private fun generatedProgress(update: GrantGeneratedQrLoginProgress) {
        when (update) {
            GrantGeneratedQrLoginProgress.Starting -> Unit
            is GrantGeneratedQrLoginProgress.QrReady ->
                _state.update {
                    it.copy(qrCode = Base64.encodeToString(update.qrCode.toBytes(), Base64.NO_WRAP), showPhase = ShowPhase.SHOWING_CODE)
                }
            is GrantGeneratedQrLoginProgress.QrScanned -> {
                checkCodeSender = update.checkCodeSender
                _state.update { it.copy(requestedAt = Instant.now(), typedCheckCode = "", showPhase = ShowPhase.ENTER_CHECK_CODE) }
            }
            is GrantGeneratedQrLoginProgress.WaitingForAuth -> {
                verificationUri = update.verificationUri
                continuationSender = update.continuationSender
                _state.update { it.copy(step = Step.WARNING) }
            }
            GrantGeneratedQrLoginProgress.SyncingSecrets -> _state.update { it.copy(approvePhase = ApprovePhase.FINISHING) }
            GrantGeneratedQrLoginProgress.Done -> {
                _state.update { it.copy(approvePhase = ApprovePhase.DONE) }
                scope.launch { finish() }
            }
        }
    }

    /** Action editCheckCode: ASCII digits, at most 2; at 2 digits, submitCheckCode. */
    fun editCheckCode(text: String) {
        if (_state.value.showPhase != ShowPhase.ENTER_CHECK_CODE) return
        val digits = text.filter { it in '0'..'9' }.take(2)
        _state.update { it.copy(typedCheckCode = digits) }
        val sender = checkCodeSender
        if (digits.length < 2 || sender == null) return
        checkCodeSender = null
        _state.update { it.copy(showPhase = ShowPhase.CHECKING) }
        val current = codeAttempt
        scope.launch {
            try {
                sender.send(digits.toInt().toUByte())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A wrong number also ends the generate task with invalidCheckCode.
                if (codeAttempt == current) taskFailed(e)
            }
        }
    }

    /** Action newCode: drops the handler and makes a new code. */
    fun newCode() {
        codeAttempt += 1
        grantJob?.cancel()
        grantJob = null
        continuationSender = null
        checkCodeSender = null
        _state.update { it.copy(step = Step.SHOW, qrCode = null, typedCheckCode = "", requestedAt = null) }
        generate()
    }

    /** Action progress. */
    private fun progress(update: GrantQrLoginProgress) {
        when (update) {
            GrantQrLoginProgress.Starting -> Unit
            is GrantQrLoginProgress.EstablishingSecureChannel ->
                _state.update { it.copy(checkCode = update.checkCodeString, scanPhase = ScanPhase.SHOW_CHECK_CODE) }
            is GrantQrLoginProgress.WaitingForAuth -> {
                verificationUri = update.verificationUri
                continuationSender = update.continuationSender
                _state.update { it.copy(step = Step.WARNING) }
            }
            GrantQrLoginProgress.SyncingSecrets -> _state.update { it.copy(approvePhase = ApprovePhase.FINISHING) }
            GrantQrLoginProgress.Done -> {
                _state.update { it.copy(approvePhase = ApprovePhase.DONE) }
                scope.launch { finish() }
            }
        }
    }

    /** Action taskFailed: maps the SDK error to a string. */
    private fun taskFailed(error: Exception) {
        grantJob = null
        val failure = when (error) {
            is HumanQrGrantLoginException.NotFound, is HumanQrGrantLoginException.Expired -> Failure.EXPIRED
            is HumanQrGrantLoginException.ConnectionInsecure -> Failure.INSECURE
            is HumanQrGrantLoginException.InvalidCheckCode -> if (mode == Mode.PHONE) Failure.WRONG_CHECK_CODE else Failure.INSECURE
            is HumanQrGrantLoginException.MissingSecretsBackup -> Failure.NEEDS_RECOVERY_KEY
            is HumanQrGrantLoginException.Cancelled -> Failure.STOPPED
            is HumanQrGrantLoginException.UnsupportedQrCodeType,
            is HumanQrGrantLoginException.UnsupportedProtocol,
            is HumanQrGrantLoginException.OtherDeviceAlreadySignedIn,
            -> Failure.NOT_BIPE_CODE
            is BipeApiException.Network -> Failure.NETWORK
            else -> Failure.GENERIC
        }
        val state = _state.value
        if (state.isClosed) return
        when (state.step) {
            Step.SCAN, Step.SHOW, Step.WARNING -> when (mode) {
                Mode.COMPUTER -> _state.update { it.copy(step = Step.SCAN, scanError = failure, scanPhase = ScanPhase.FAILED) }
                // A second error for the same try (send, then the task) keeps the first.
                Mode.PHONE -> if (state.showPhase != ShowPhase.FAILED) {
                    _state.update { it.copy(step = Step.SHOW, showError = failure, showPhase = ShowPhase.FAILED) }
                }
            }
            Step.APPROVE -> {
                // Cancelled after the consent page: the user declined on the MAS page.
                if (failure == Failure.STOPPED && sawConsentPage) {
                    close()
                    return
                }
                _state.update { it.copy(approveError = if (failure == Failure.EXPIRED) Failure.EXPIRED else Failure.GENERIC, approvePhase = ApprovePhase.FAILED) }
                scope.launch { finish() }
            }
        }
    }

    /** Action scanAgain: drops the task and scans again. */
    fun scanAgain() {
        grantJob?.cancel()
        grantJob = null
        continuationSender = null
        _state.update { it.copy(step = Step.SCAN, scanPhase = ScanPhase.SCANNING, checkCode = null, scanError = null) }
    }

    /** Action appear of the warning: the countdown from the start, also when the screen comes back to the front. */
    fun warningAppeared() {
        countdown?.cancel()
        _state.update { it.copy(secondsLeft = BipeRegistries.WEB_LINK_COOLDOWN_SECONDS) }
        countdown = scope.launch {
            while (_state.value.secondsLeft > 0) {
                delay(1_000)
                _state.update { it.copy(secondsLeft = it.secondsLeft - 1) }
            }
        }
    }

    fun warningLeftFront() {
        countdown?.cancel()
    }

    /** Action continue: only when canContinue. */
    fun continueToApprove() {
        if (!_state.value.canContinue) return
        countdown?.cancel()
        _state.update { it.copy(step = Step.APPROVE, approvePhase = ApprovePhase.CONFIRM_IDENTITY) }
    }

    /** Action cancel (scan and warning): the computer does not sign in. */
    fun cancel() {
        val sender = continuationSender
        continuationSender = null
        checkCodeSender = null
        grantJob?.cancel()
        grantJob = null
        countdown?.cancel()
        _state.update { it.copy(isClosed = true) }
        scope.launch { runCatching { sender?.cancel() } }
    }

    /** The server check of the Bipe PIN, for the owner check on a phone with no strong biometrics (bipe-chat/bipe#785). */
    suspend fun checkAccountPin(pin: String) {
        settings.authorized { settings.api.checkPin(pin, it) }
    }

    /** Action confirmIdentity, after the owner check of the screen. A fail goes back to the warning. */
    fun identityChecked(passed: Boolean) {
        scope.launch {
            val secret = if (passed) hiddenPassword(session.userId) else null
            if (secret == null) {
                _state.update { it.copy(step = Step.WARNING) }
                return@launch
            }
            password = secret
            _state.update { it.copy(approvePhase = ApprovePhase.LOADING) }
            openConsent()
        }
    }

    /** Action open: the MAS host from the auth metadata; the verification page must be on it. */
    private suspend fun openConsent() {
        val host = runCatching { matrix.authMetadata().host }.getOrNull()
        val url = verificationUri
        if (host == null || url == null || !BipeWebLink.isAllowed(url, host)) {
            fail(Failure.SIGN_IN)
            return
        }
        _state.update { it.copy(masHost = host, consentUrl = url) }
    }

    /** The script that fills the MAS login form once. null when nothing is to be filled. */
    fun pageLoaded(url: String, hasPasswordField: Boolean, hasForm: Boolean, text: String): String? {
        if (hasPasswordField) {
            val secret = password
            if (filledLoginForm || secret == null) {
                // A second login form: a wrong password or a MAS rate limit.
                fail(Failure.SIGN_IN)
                return null
            }
            filledLoginForm = true
            password = null
            return BipeWebLink.loginScript(BipeWebLink.localpart(session.userId), secret)
        }
        if (BipeLinkComputer.isDeviceConsentPath(url) && hasForm) {
            if (!sawConsentPage) {
                sawConsentPage = true
                _state.update { it.copy(approvePhase = ApprovePhase.CONSENT) }
                val ip = BipeLinkComputer.firstIpAddress(text)
                if (ip == null) {
                    _state.update { it.copy(placeKnown = true) }
                } else {
                    scope.launch { lookUpPlace(ip) }
                }
            }
            return null
        }
        if (sawConsentPage && _state.value.approvePhase == ApprovePhase.CONSENT) {
            // The user approved or declined; the SDK learns the result from MAS.
            _state.update { it.copy(approvePhase = ApprovePhase.FINISHING) }
            val sender = continuationSender
            scope.launch {
                runCatching { sender?.confirm() }
                finish()
            }
        }
        return null
    }

    /** A navigation off the MAS host stops the load. */
    fun blockedNavigation() = fail(Failure.SIGN_IN)

    /** Action close. */
    fun close() {
        val sender = continuationSender
        val confirm = sawConsentPage
        _state.update { it.copy(isClosed = true) }
        scope.launch {
            runCatching { if (confirm) sender?.confirm() else sender?.cancel() }
            finish()
        }
    }

    /** Action retry: a new QR code is needed for each try. */
    fun retry() {
        scope.launch { finish() }
        password = null
        filledLoginForm = false
        sawConsentPage = false
        verificationUri = null
        _state.update { it.copy(place = null, placeKnown = false, approveError = null, consentUrl = null, masHost = null) }
        finished = false
        when (mode) {
            Mode.COMPUTER -> scanAgain()
            Mode.PHONE -> newCode()
        }
    }

    private fun fail(failure: Failure) {
        password = null
        _state.update { it.copy(approveError = failure, approvePhase = ApprovePhase.FAILED) }
        val sender = continuationSender
        scope.launch {
            runCatching { sender?.cancel() }
            finish()
        }
    }

    private suspend fun lookUpPlace(ip: String) {
        val place = runCatching { settings.authorized { settings.api.webLinkPlace(ip, it) }.label() }.getOrNull()
        _state.update { it.copy(place = place, placeKnown = true) }
    }

    /**
     * Action finish: once per flow. The screen clears the web view cookies and storage; bipe-api ends the MAS browser
     * sessions the web view made. A failure is logged and not shown.
     */
    private suspend fun finish() {
        if (finished || _state.value.masHost == null) return
        finished = true
        val since = startedAt
        runCatching { settings.authorized { settings.api.finishWebLink(since, it) } }
            .onFailure { android.util.Log.w("Bipe", "web-link finish failed") }
    }
}
