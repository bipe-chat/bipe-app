// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.linkcomputer

import android.content.Context
import android.util.Base64
import android.util.Log
import chat.bipe.app.BuildConfig
import chat.bipe.app.settings.SettingsModel
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeAccountStatus
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeSeat
import chat.bipe.shared.api.seats
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.settings.checkPin
import chat.bipe.shared.weblink.BipeLinkComputer
import chat.bipe.shared.weblink.BipeMatrixAccountApi
import chat.bipe.shared.weblink.BipeWebLink
import chat.bipe.shared.weblink.MasDeviceApproval
import chat.bipe.shared.weblink.WebLinkKind
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
 * scans the QR code of the computer, opens the MSC4108 secure channel, warns, waits through the cooldown, checks the
 * owner and approves on a native consent screen; MasDeviceApproval posts the MAS forms with no web view (ADR 0116,
 * ADR 0118, ADR 0224).
 *
 * Mode PHONE is specs link-phone-show and link-phone-approve: this old phone shows the code, the new phone scans it
 * (ADR 0059), and the same warning, owner check and consent screen approve it.
 */
class LinkComputerModel(
    private val context: Context,
    private val settings: SettingsModel,
    private val scope: CoroutineScope,
    val mode: Mode = Mode.COMPUTER,
    /** Mode PHONE, spec company-team-add: the person this phone adds. */
    val person: PersonToAdd? = null,
    /** The grant finished: the person's phone has its session and can claim the seat. */
    private val onGrantDone: (() -> Unit)? = null,
) {
    data class PersonToAdd(val seatId: String, val name: String, val accountName: String)

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
        /** MissingSecretsBackup with a cross-signing cause: this phone lacks a private cross-signing key. */
        NEEDS_APPROVAL(R.string.bipe_app_link_computer_needs_approval, R.string.bipe_app_link_phone_needs_approval),
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
        /** The device card text and the IP of the MAS consent page. */
        val device: String? = null,
        val ip: String? = null,
        val masHost: String? = null,
        /** State errorCode: a short code for support under the error. Never a key or a secret. */
        val errorCode: String? = null,
        val isClosed: Boolean = false,
        /** Action approvePhone closed the flow: the screen opens the encryption-status sheet after it closes. */
        val opensApproval: Boolean = false,
        /** Mode COMPUTER on a work account with more than one person: who the web session belongs to. */
        val people: List<BipeSeat> = emptyList(),
        val pickedSeatId: String? = null,
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
    private var approval: MasDeviceApproval? = null
    private var consent: MasDeviceApproval.Consent? = null
    /** Approve posted the MAS consent form: close then confirms. */
    private var approved = false
    /** Reject: a cancel from the SDK is the decline and closes with no message. */
    private var rejected = false
    private var finished = false
    /** Held only between the owner check and the one post of the MAS login form. */
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
            if (support == Support.SUPPORTED && mode == Mode.COMPUTER) loadPeople()
        }
    }

    /** Spec link-computer-approve pickPerson. A personal account answers not_found and the list stays empty. */
    private suspend fun loadPeople() {
        val list = runCatching { settings.authorized { settings.api.seats(it) } }.getOrNull() ?: return
        if (list.me.role != "owner" || list.activeSeats.size < 2) return
        _state.update { it.copy(people = list.activeSeats, pickedSeatId = list.me.seatId) }
    }

    fun pickPerson(seatId: String) {
        _state.update { it.copy(pickedSeatId = seatId) }
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
        _state.update { it.copy(scanError = null, errorCode = null, scanPhase = ScanPhase.CONNECTING) }
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
        _state.update { it.copy(showError = null, errorCode = null, showPhase = ShowPhase.PREPARING) }
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
                onGrantDone?.invoke()
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
        var code = if (error is HumanQrGrantLoginException) error::class.simpleName else null
        val failure = when (error) {
            is HumanQrGrantLoginException.NotFound, is HumanQrGrantLoginException.Expired -> Failure.EXPIRED
            is HumanQrGrantLoginException.ConnectionInsecure -> Failure.INSECURE
            is HumanQrGrantLoginException.InvalidCheckCode -> if (mode == Mode.PHONE) Failure.WRONG_CHECK_CODE else Failure.INSECURE
            is HumanQrGrantLoginException.MissingSecretsBackup -> secretsCause(error.message.orEmpty()).also { code = it.second }.first
            is HumanQrGrantLoginException.Cancelled -> Failure.STOPPED
            is HumanQrGrantLoginException.UnsupportedQrCodeType,
            is HumanQrGrantLoginException.UnsupportedProtocol,
            is HumanQrGrantLoginException.OtherDeviceAlreadySignedIn,
            -> Failure.NOT_BIPE_CODE
            is BipeApiException.Network -> Failure.NETWORK
            else -> Failure.GENERIC
        }
        // The SDK message can name the account or the server: debug builds only (bipe-chat/bipe#1122).
        Log.e(TAG, "link failed (${code ?: "-"}): ${error::class.simpleName}" + if (BuildConfig.DEBUG) ": ${error.message}" else "")
        _state.update { it.copy(errorCode = code) }
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
                // Cancelled after reject: the user declined.
                if (failure == Failure.STOPPED && rejected) {
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
        _state.update { it.copy(step = Step.SCAN, scanPhase = ScanPhase.SCANNING, checkCode = null, scanError = null, errorCode = null) }
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

    /** Action approvePhone: closes the flow; the screen then opens the encryption-status sheet. */
    fun approvePhone() {
        _state.update { it.copy(opensApproval = true) }
        cancel()
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

    /** GET /v1/me/account, for the ways out of the Bipe PIN pad (ADR 0283 rule 12). */
    suspend fun readAccount(): BipeAccountStatus = settings.authorized { settings.api.account(it) }

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

    /**
     * Action open: the MAS host from the auth metadata; the verification page must be on it. Then the MAS pages with
     * the hidden password, up to the consent page (ADR 0224).
     */
    private suspend fun openConsent() {
        val host = runCatching { matrix.authMetadata().host }.getOrNull()
        val url = verificationUri
        val secret = password
        password = null
        if (host == null || url == null || secret == null || !BipeWebLink.isAllowed(url, host)) {
            Log.e(TAG, "link failed (consent-host): no auth metadata or the page is off the MAS host")
            _state.update { it.copy(errorCode = "consent-host") }
            fail(Failure.SIGN_IN)
            return
        }
        _state.update { it.copy(masHost = host) }
        val approval = MasDeviceApproval(host)
        this.approval = approval
        try {
            val consent = approval.open(url, BipeWebLink.localpart(session.userId), secret)
            if (_state.value.approvePhase != ApprovePhase.LOADING) return
            this.consent = consent
            _state.update { it.copy(device = consent.device, ip = consent.ip, approvePhase = ApprovePhase.CONSENT) }
            val ip = consent.ip
            if (ip == null) _state.update { it.copy(placeKnown = true) } else lookUpPlace(ip)
        } catch (error: MasDeviceApproval.Failure) {
            approvalFailed(error)
        }
    }

    /** Action approve: posts the MAS consent form; MAS then grants the computer's request. */
    fun approve() {
        val approval = approval ?: return
        val consent = consent ?: return
        if (_state.value.approvePhase != ApprovePhase.CONSENT) return
        _state.update { it.copy(approvePhase = ApprovePhase.FINISHING) }
        scope.launch {
            try {
                approval.approve(consent)
                approved = true
                runCatching { continuationSender?.confirm() }
            } catch (error: MasDeviceApproval.Failure) {
                approvalFailed(error)
            }
        }
    }

    /** Action reject: posts the MAS reject form, then the SDK cancel closes the flow with no message. */
    fun reject() {
        val approval = approval ?: return
        val consent = consent ?: return
        if (_state.value.approvePhase != ApprovePhase.CONSENT) return
        rejected = true
        _state.update { it.copy(isClosed = true) }
        val sender = continuationSender
        scope.launch {
            approval.reject(consent)
            runCatching { sender?.cancel() }
            finish()
        }
    }

    private fun approvalFailed(error: MasDeviceApproval.Failure) {
        val page = (error as? MasDeviceApproval.Failure.NotConsentPage)?.let { ": page ${it.path}" }.orEmpty()
        Log.e(TAG, "link failed (${error.code})$page")
        _state.update { it.copy(errorCode = error.code) }
        fail(if (error is MasDeviceApproval.Failure.Network) Failure.NETWORK else Failure.SIGN_IN)
    }

    /** Action close. */
    fun close() {
        val sender = continuationSender
        val confirm = approved
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
        approved = false
        rejected = false
        consent = null
        verificationUri = null
        _state.update { it.copy(place = null, placeKnown = false, approveError = null, device = null, ip = null, masHost = null) }
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
     * Action finish: once per flow. The approval drops its cookies; bipe-api ends the MAS browser sessions it made. A
     * failure is logged and not shown.
     */
    private suspend fun finish() {
        if (finished || _state.value.masHost == null) return
        finished = true
        approval?.close()
        approval = null
        consent = null
        val since = startedAt
        val kind = if (mode == Mode.COMPUTER) WebLinkKind.COMPUTER else WebLinkKind.PHONE
        val seatId = person?.seatId ?: _state.value.pickedSeatId
        runCatching { settings.authorized { settings.api.finishWebLink(since, it, kind, seatId) } }
            .onFailure { android.util.Log.w("Bipe", "web-link finish failed") }
    }

    companion object {
        private const val TAG = "LinkDevice"

        /**
         * State errorCode: the cause of a MissingSecretsBackup. The SDK sends it as text only
         * (SecretsBundleExportError, matrix-sdk-crypto export_secrets_bundle).
         */
        fun secretsCause(detail: String): Pair<Failure, String> {
            val text = detail.lowercase()
            return when {
                "cross-signing" in text -> Failure.NEEDS_APPROVAL to "keys-missing"
                "backup version" in text -> Failure.GENERIC to "backup-version"
                else -> Failure.GENERIC to "no-crypto"
            }
        }
    }

}
