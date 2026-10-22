// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SessionStore
import chat.bipe.app.common.OwnerCheck
import chat.bipe.app.common.SignInTimes
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeRecovery
import chat.bipe.shared.api.BipeRecoveryMethod
import chat.bipe.shared.api.BipeRecoveryPurpose
import chat.bipe.shared.api.BipeRecoveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec recovery-waiting (bipe-shared product/screens/recovery-waiting.json). */
class RecoveryWaitingModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val vault: BipeAccountVault,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    private val onSignedIn: (AccountSession) -> Unit,
    private val onStartOver: () -> Unit,
) {
    enum class Phase { WAITING, CLAIMING, READY, CANCELLED, EXPIRED, FAILED }

    private val _phase = MutableStateFlow(Phase.WAITING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _purpose = MutableStateFlow(BipeRecoveryPurpose.ACCESS)
    val purpose: StateFlow<BipeRecoveryPurpose> = _purpose.asStateFlow()
    private val _readyAt = MutableStateFlow(System.currentTimeMillis())
    val readyAt: StateFlow<Long> = _readyAt.asStateFlow()
    private var timer: Job? = null

    /** Action appear: polls the claim on open, on foreground and at readyAt. */
    fun appear() {
        scope.launch {
            vault.read().claim?.let { kept ->
                _purpose.value = BipeRecoveryPurpose.entries.firstOrNull { it.id == kept.purposeId } ?: BipeRecoveryPurpose.ACCESS
                _readyAt.value = kept.readyAtMillis
            }
            claim()
            timer?.cancel()
            val wait = _readyAt.value - System.currentTimeMillis()
            if (_phase.value == Phase.WAITING && wait > 0) {
                timer = scope.launch {
                    delay(wait + 1_000)
                    claim()
                }
            }
        }
    }

    /** Action claim. */
    suspend fun claim() {
        val kept = vault.read().claim ?: return
        val before = _phase.value
        if (_readyAt.value <= System.currentTimeMillis()) _phase.value = Phase.CLAIMING
        try {
            val result = api.claimRecovery(kept.recoveryId, kept.claimSecret)
            when (result.state) {
                BipeRecoveryState.WAITING, null -> {
                    _phase.value = Phase.WAITING
                    result.readyAt?.let { _readyAt.value = it.toEpochMilli() }
                }
                BipeRecoveryState.COMPLETED -> {
                    val credentials = result.credentials
                    vault.update { it.copy(claim = null) }
                    if (credentials != null) {
                        vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
                        val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
                        _phase.value = Phase.READY
                        onSignedIn(session)
                    } else {
                        // pinReset: the new PIN is in force.
                        _phase.value = Phase.READY
                    }
                }
                BipeRecoveryState.CANCELLED -> _phase.value = Phase.CANCELLED
                BipeRecoveryState.EXPIRED -> _phase.value = Phase.EXPIRED
            }
        } catch (error: BipeApiException) {
            _phase.value = if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.RECOVERY_INVALID) {
                Phase.EXPIRED
            } else if (before == Phase.CLAIMING) {
                Phase.FAILED
            } else {
                before
            }
        } catch (error: Exception) {
            _phase.value = Phase.FAILED
        }
    }

    fun retry() {
        scope.launch { claim() }
    }

    /** Action startOver: from cancelled or expired. */
    fun startOver() {
        scope.launch {
            vault.update { it.copy(claim = null) }
            onStartOver()
        }
    }
}

/** Spec recovery-alert (bipe-shared product/screens/recovery-alert.json). */
class RecoveryAlertModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    enum class Phase { HIDDEN, SHOWN, CANCELLING, CANCELLED, FAILED }

    private val _phase = MutableStateFlow(Phase.HIDDEN)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _notice = MutableStateFlow<BipeRecovery?>(null)
    val notice: StateFlow<BipeRecovery?> = _notice.asStateFlow()
    private val preferences = context.getSharedPreferences("bipe.recovery", Context.MODE_PRIVATE)
    private val dismissedKey = "dismissed.${session.userId}"

    /** Action check: on launch and on foreground. */
    fun check() {
        if (_phase.value != Phase.HIDDEN) return
        scope.launch {
            val status = runCatching { BipeApiClient.withTokenRetry({ session.accessToken() }) { api.account(it) } }.getOrNull() ?: return@launch
            val dismissed = preferences.getStringSet(dismissedKey, emptySet()).orEmpty()
            val signedInAt = SignInTimes(context).value(session.userId)
            _notice.value = status.recoveries.firstOrNull { recovery ->
                // A recovery older than this phone's sign-in is not news for this phone.
                alertMessage(recovery) != null && recovery.id !in dismissed && recovery.createdAt.toEpochMilli() > signedInAt
            }
            if (_notice.value != null) _phase.value = Phase.SHOWN
        }
    }

    /** Action cancel: after the owner check. [activity] shows the check. */
    fun cancel(activity: Context) {
        val notice = _notice.value ?: return
        if (!canBeCancelled(notice)) return
        scope.launch {
            if (!OwnerCheck.confirm(activity, context.getString(R.string.bipe_recovery_alert_title))) return@launch
            _phase.value = Phase.CANCELLING
            _phase.value = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { api.cancelRecovery(notice.id, it) }
            }.fold(
                onSuccess = {
                    dismiss(notice.id)
                    Phase.CANCELLED
                },
                onFailure = { Phase.FAILED },
            )
        }
    }

    /** Action keep: "It was me". The recovery goes on. */
    fun keep() {
        _notice.value?.let { dismiss(it.id) }
        _phase.value = Phase.HIDDEN
    }

    fun close() {
        _phase.value = Phase.HIDDEN
    }

    private fun dismiss(id: String) {
        val dismissed = preferences.getStringSet(dismissedKey, emptySet()).orEmpty() + id
        preferences.edit().putStringSet(dismissedKey, dismissed.toList().takeLast(50).toSet()).apply()
    }

    /** Only a waiting recovery can still be cancelled. */
    fun canBeCancelled(recovery: BipeRecovery): Boolean = recovery.state == BipeRecoveryState.WAITING

    /** The alert text by method and purpose (spec notes). `null`: this recovery needs no alert. */
    fun alertMessage(recovery: BipeRecovery): String? {
        val date = android.text.format.DateUtils.formatDateTime(
            context,
            (recovery.readyAt ?: recovery.createdAt).toEpochMilli(),
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME,
        )
        return when {
            recovery.state == BipeRecoveryState.WAITING && recovery.purpose == BipeRecoveryPurpose.PIN_RESET ->
                context.getString(R.string.bipe_recovery_alert_pin_reset, date)
            recovery.state == BipeRecoveryState.WAITING && recovery.method == BipeRecoveryMethod.GUARDIAN ->
                context.getString(R.string.bipe_recovery_alert_guardian, date)
            recovery.state == BipeRecoveryState.WAITING && recovery.method == BipeRecoveryMethod.CPF ->
                context.getString(R.string.bipe_recovery_alert_cpf, date)
            recovery.state == BipeRecoveryState.WAITING -> context.getString(R.string.bipe_recovery_alert_email, date)
            recovery.state == BipeRecoveryState.COMPLETED && recovery.purpose == BipeRecoveryPurpose.ACCESS &&
                recovery.method == BipeRecoveryMethod.TOKEN -> context.getString(R.string.bipe_recovery_alert_token)
            else -> null
        }
    }
}
