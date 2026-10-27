// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails

/** Type VerificationSymbol of spec device-verification. */
data class VerificationSymbol(val id: Int, val symbol: String, val label: String)

/** Type RequestingDevice of spec device-verification. */
data class RequestingDevice(val deviceId: String, val displayName: String?, val firstSeenAt: Long?)

/**
 * Spec device-verification (bipe-shared product/screens/device-verification.json).
 * Lives for the whole session, so a request from a new phone reaches this device on any screen.
 */
class DeviceVerificationModel(private val session: AccountSession, private val scope: CoroutineScope) {
    enum class Role { NEW_PHONE, APPROVER }

    enum class Phase { REQUESTING, WAITING_FOR_OTHER, INCOMING, CONFIRM_IDENTITY, COMPARE, WAITING_FOR_MATCH, DONE, CANCELLED, FAILED }

    /** The calls of SessionVerificationControllerDelegate, as values. */
    private sealed interface Event {
        data class ReceivedRequest(val details: SessionVerificationRequestDetails) : Event
        data object Accepted : Event
        data object StartedSas : Event
        data class ReceivedData(val data: SessionVerificationData) : Event
        data object Failed : Event
        data object Cancelled : Event
        data object Finished : Event
    }

    private val _role = MutableStateFlow(Role.NEW_PHONE)
    val role: StateFlow<Role> = _role.asStateFlow()

    /** No flow runs yet: the idle model counts as finished (isFinished). */
    private val _phase = MutableStateFlow(Phase.CANCELLED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _otherDevice = MutableStateFlow<RequestingDevice?>(null)
    val otherDevice: StateFlow<RequestingDevice?> = _otherDevice.asStateFlow()
    private val _symbols = MutableStateFlow<List<VerificationSymbol>>(emptyList())
    val symbols: StateFlow<List<VerificationSymbol>> = _symbols.asStateFlow()

    /** The flow ended with noMatch: the screen shows bipe_app_verify_mismatch. */
    private val _mismatch = MutableStateFlow(false)
    val mismatch: StateFlow<Boolean> = _mismatch.asStateFlow()

    /** Action notifyAgain is open: newPhone waits and the last nudge is APPROVAL_NUDGE_AGAIN_SECONDS old. */
    private val _canNotifyAgain = MutableStateFlow(false)
    val canNotifyAgain: StateFlow<Boolean> = _canNotifyAgain.asStateFlow()

    private val api = BipeApiClient(AppConfig.API_BASE_URL)
    private var nudgeWait: Job? = null

    /** Called when a request from another device of this account arrives (action receive). */
    var onIncoming: (() -> Unit)? = null

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var controller: SessionVerificationController? = null
    private var job: Job? = null

    /** The flow has ended or has not started; a new request may begin. */
    val isFinished: Boolean
        get() = _phase.value in setOf(Phase.DONE, Phase.CANCELLED, Phase.FAILED)

    /** Gets the controller and listens to its delegate for the life of the session. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            // The controller needs the account's own identity; the SDK has it after the first sync.
            session.client.encryption().waitForE2eeInitializationTasks()
            var found: SessionVerificationController? = null
            for (attempt in 0 until 10) {
                found = runCatching { session.client.getSessionVerificationController() }.getOrNull()
                if (found != null) break
                delay(minOf(2_000L shl attempt, 30_000L))
            }
            val controller = found ?: return@launch
            controller.setDelegate(Delegate(events))
            this@DeviceVerificationModel.controller = controller
            for (event in events) {
                handle(event)
            }
        }
    }

    fun close() {
        job?.cancel()
        controller?.setDelegate(null)
    }

    /** Action request: newPhone asks the other devices of the account. */
    fun request() {
        _role.value = Role.NEW_PHONE
        _otherDevice.value = null
        _symbols.value = emptyList()
        _mismatch.value = false
        _canNotifyAgain.value = false
        _phase.value = Phase.REQUESTING
        scope.launch {
            val controller = readyController()
            if (controller == null) {
                _phase.value = Phase.FAILED
                return@launch
            }
            _phase.value = runCatching { controller.requestDeviceVerification() }
                .fold({ Phase.WAITING_FOR_OTHER }, { Phase.FAILED })
            if (_phase.value == Phase.WAITING_FOR_OTHER) nudge()
        }
    }

    /** Action notifyAgain: the push to the other devices once more (bipe-chat/bipe#628). */
    fun notifyAgain() {
        if (_role.value != Role.NEW_PHONE || _phase.value != Phase.WAITING_FOR_OTHER || !_canNotifyAgain.value) return
        scope.launch { nudge() }
    }

    /** POST /v1/me/approval-nudge. Best effort: a failure, rate_limited included, does not change the phase. */
    private suspend fun nudge() {
        _canNotifyAgain.value = false
        nudgeWait?.cancel()
        runCatching { BipeApiClient.withTokenRetry({ session.accessToken() }) { api.sendApprovalNudge(it) } }
        nudgeWait = scope.launch {
            delay(BipeRegistries.APPROVAL_NUDGE_AGAIN_SECONDS * 1_000L)
            if (_role.value == Role.NEW_PHONE && _phase.value == Phase.WAITING_FOR_OTHER) _canNotifyAgain.value = true
        }
    }

    /** Action accept: approver passes the device owner check, then accepts. */
    fun accept(confirmOwner: suspend () -> Boolean) {
        val controller = controller ?: return
        if (_role.value != Role.APPROVER || _phase.value != Phase.INCOMING) return
        _phase.value = Phase.CONFIRM_IDENTITY
        scope.launch {
            if (!confirmOwner()) {
                _phase.value = Phase.INCOMING
                return@launch
            }
            _phase.value = runCatching { controller.acceptVerificationRequest() }
                .fold({ Phase.WAITING_FOR_OTHER }, { Phase.FAILED })
        }
    }

    /** Action decline: approver says no to the request. */
    fun decline() {
        _phase.value = Phase.CANCELLED
        scope.launch { runCatching { controller?.declineVerification() } }
    }

    /** Action match: the symbols are the same on both devices. */
    fun match() {
        val controller = controller ?: return
        if (_phase.value != Phase.COMPARE) return
        _phase.value = Phase.WAITING_FOR_MATCH
        scope.launch {
            runCatching { controller.approveVerification() }.onFailure { _phase.value = Phase.FAILED }
        }
    }

    /** Action noMatch: the symbols differ. Nothing is approved. */
    fun noMatch() {
        if (_phase.value != Phase.COMPARE) return
        _mismatch.value = true
        _phase.value = Phase.CANCELLED
        scope.launch { runCatching { controller?.declineVerification() } }
    }

    /** Action cancel: at any time before done. */
    fun cancel() {
        if (isFinished) return
        _phase.value = Phase.CANCELLED
        scope.launch { runCatching { controller?.cancelVerification() } }
    }

    /** The controller, after start got it. A tap on the banner can come before that. */
    private suspend fun readyController(): SessionVerificationController? {
        repeat(30) {
            controller?.let { return it }
            delay(1_000)
        }
        return controller
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.ReceivedRequest -> {
                // Action receive: only requests from this account's own devices (spec notes).
                val details = event.details
                if (details.senderProfile.userId != session.userId) return
                if (!isFinished && _phase.value != Phase.INCOMING) return
                runCatching { controller?.acknowledgeVerificationRequest(details.senderProfile.userId, details.flowId) }
                    .onFailure { return }
                _role.value = Role.APPROVER
                _otherDevice.value = RequestingDevice(details.deviceId, details.deviceDisplayName, details.firstSeenTimestamp.toLong())
                _symbols.value = emptyList()
                _mismatch.value = false
                _phase.value = Phase.INCOMING
                onIncoming?.invoke()
            }
            // The other device accepted the request of this new phone: start SAS.
            Event.Accepted -> if (_role.value == Role.NEW_PHONE && _phase.value == Phase.WAITING_FOR_OTHER) {
                runCatching { controller?.startSasVerification() }.onFailure { _phase.value = Phase.FAILED }
            }
            Event.StartedSas -> Unit
            // After match the flow waits for the other device; data that arrives again does not undo the match.
            is Event.ReceivedData -> if (!isFinished && _phase.value != Phase.WAITING_FOR_MATCH) {
                _symbols.value = symbols(event.data)
                _phase.value = Phase.COMPARE
            }
            Event.Finished -> _phase.value = Phase.DONE
            Event.Cancelled -> if (_phase.value != Phase.DONE) _phase.value = Phase.CANCELLED
            Event.Failed -> if (_phase.value != Phase.DONE) _phase.value = Phase.FAILED
        }
    }

    private fun symbols(data: SessionVerificationData): List<VerificationSymbol> = when (data) {
        is SessionVerificationData.Emojis -> data.emojis.mapIndexed { index, emoji -> VerificationSymbol(index, emoji.symbol(), emoji.description()) }
        is SessionVerificationData.Decimals -> data.values.mapIndexed { index, value -> VerificationSymbol(index, value.toString(), "") }
    }

    /** SDK delegate calls come on SDK threads; the channel hands them to the model in order. */
    private class Delegate(private val events: Channel<Event>) : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
            events.trySend(Event.ReceivedRequest(details))
        }

        override fun didAcceptVerificationRequest() {
            events.trySend(Event.Accepted)
        }

        override fun didStartSasVerification() {
            events.trySend(Event.StartedSas)
        }

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            events.trySend(Event.ReceivedData(data))
        }

        override fun didFail() {
            events.trySend(Event.Failed)
        }

        override fun didCancel() {
            events.trySend(Event.Cancelled)
        }

        override fun didFinish() {
            events.trySend(Event.Finished)
        }
    }
}
