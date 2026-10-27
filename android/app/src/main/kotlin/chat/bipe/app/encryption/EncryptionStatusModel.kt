// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.content.Context
import chat.bipe.app.account.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.BackupStateListener
import org.matrix.rustcomponents.sdk.RecoveryException
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener

/**
 * Spec encryption-status (bipe-shared product/screens/encryption-status.json): the one place that
 * explains missing history (ADR 0073). It also opens device-verification and recovery-key-enter.
 */
class EncryptionStatusModel(
    private val session: AccountSession,
    private val scope: CoroutineScope,
    context: Context,
) {
    /** The sheet that is open. HISTORY is the sheet of spec encryption-status (state sheetOpen). */
    enum class Route { HISTORY, VERIFICATION, RECOVERY_KEY }

    private val encryption = session.client.encryption()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val bannerKey = "bannerDismissedAt.${session.userId}"

    private val _verificationState = MutableStateFlow(VerificationState.UNKNOWN)
    val verificationState: StateFlow<VerificationState> = _verificationState.asStateFlow()
    private val _recoveryState = MutableStateFlow(RecoveryState.UNKNOWN)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    private val _backupState = MutableStateFlow(BackupState.UNKNOWN)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()
    private val _hasOtherDevices = MutableStateFlow(false)
    val hasOtherDevices: StateFlow<Boolean> = _hasOtherDevices.asStateFlow()
    /** State seesUndecryptable: this phone met an undecryptable message in this app run (bipe-chat/bipe#853). */
    private val _seesUndecryptable = MutableStateFlow(false)
    val seesUndecryptable: StateFlow<Boolean> = _seesUndecryptable.asStateFlow()
    private val _bannerDismissed = MutableStateFlow(System.currentTimeMillis() - prefs.getLong(bannerKey, 0) < BANNER_PAUSE_MS)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    val verification = DeviceVerificationModel(session, scope)
    private val handles = mutableListOf<TaskHandle>()
    private var started = false

    init {
        verification.onIncoming = { _route.value = Route.VERIFICATION }
    }

    /** Action observe: listens to the SDK verification, recovery and backup state. */
    fun observe() {
        if (started) return
        started = true
        val verificationUpdates = Channel<VerificationState>(Channel.UNLIMITED)
        val recoveryUpdates = Channel<RecoveryState>(Channel.UNLIMITED)
        val backupUpdates = Channel<BackupState>(Channel.UNLIMITED)
        handles += encryption.verificationStateListener(object : VerificationStateListener {
            override fun onUpdate(status: VerificationState) {
                verificationUpdates.trySend(status)
            }
        })
        handles += encryption.recoveryStateListener(object : RecoveryStateListener {
            override fun onUpdate(status: RecoveryState) {
                recoveryUpdates.trySend(status)
            }
        })
        handles += encryption.backupStateListener(object : BackupStateListener {
            override fun onUpdate(status: BackupState) {
                backupUpdates.trySend(status)
            }
        })
        _verificationState.value = encryption.verificationState()
        _recoveryState.value = encryption.recoveryState()
        _backupState.value = encryption.backupState()

        scope.launch { for (state in recoveryUpdates) _recoveryState.value = state }
        scope.launch { for (state in backupUpdates) _backupState.value = state }
        verification.start()
        scope.launch {
            refreshOtherDevices()
            for (state in verificationUpdates) {
                _verificationState.value = state
                refreshOtherDevices()
            }
        }
    }

    fun close() {
        handles.forEach { it.cancel() }
        handles.clear()
        verification.close()
    }

    /** The recovery key row shows (state recoveryState enabled or incomplete). */
    fun canUseRecoveryKey(state: RecoveryState): Boolean = state == RecoveryState.ENABLED || state == RecoveryState.INCOMPLETE

    /**
     * Spec notes: this phone is unverified and the account has history elsewhere, or messages already fail on this
     * phone. The settings row oldMessages shows while this is true, also after Skip on the banner (bipe-chat/bipe#793).
     */
    fun needsApproval(verification: VerificationState, recovery: RecoveryState, otherDevices: Boolean, undecryptable: Boolean): Boolean =
        verification == VerificationState.UNVERIFIED && (otherDevices || canUseRecoveryKey(recovery) || undecryptable)

    /** The banner: needsApproval, unless the person tapped Skip less than BANNER_PAUSE_MS ago. */
    fun showsBanner(
        verification: VerificationState,
        recovery: RecoveryState,
        otherDevices: Boolean,
        undecryptable: Boolean,
        dismissed: Boolean,
    ): Boolean = needsApproval(verification, recovery, otherDevices, undecryptable) && !dismissed

    /** Action noteUndecryptable: an undecryptable row showed, or the chat list read one. */
    fun noteUndecryptable() {
        _seesUndecryptable.value = true
    }

    /** Action openSheet. */
    fun openSheet() {
        _route.value = Route.HISTORY
        scope.launch { refreshOtherDevices() }
    }

    /** Action approveWithDevice: device-verification as newPhone. */
    fun approveWithDevice() {
        if (!_hasOtherDevices.value) return
        _route.value = Route.VERIFICATION
        verification.request()
    }

    /** Action useRecoveryKey: recovery-key-enter. */
    fun useRecoveryKey() {
        if (!canUseRecoveryKey(_recoveryState.value)) return
        _route.value = Route.RECOVERY_KEY
    }

    /** Action dismiss: hides the banner on this phone for BANNER_PAUSE_MS. */
    fun dismiss() {
        _bannerDismissed.value = true
        prefs.edit().putLong(bannerKey, System.currentTimeMillis()).apply()
    }

    /** Opens another sheet of the flow, or closes it with null. */
    fun show(route: Route?) {
        _route.value = route
    }

    /** The sheet closed. A verification that is still open ends. */
    fun sheetClosed() {
        _route.value = null
        verification.cancel()
    }

    /** Action submit of spec recovery-key-enter: Encryption.recover. */
    suspend fun recover(recoveryKey: String): RecoveryKeyEnterModel.Phase =
        try {
            encryption.recover(recoveryKey)
            RecoveryKeyEnterModel.Phase.DONE
        } catch (e: RecoveryException.SecretStorage) {
            RecoveryKeyEnterModel.Phase.WRONG_KEY
        } catch (e: RecoveryException.Import) {
            RecoveryKeyEnterModel.Phase.WRONG_KEY
        } catch (e: Exception) {
            RecoveryKeyEnterModel.Phase.FAILED
        }

    private suspend fun refreshOtherDevices() {
        runCatching { encryption.hasDevicesToVerifyAgainst() }.onSuccess { _hasOtherDevices.value = it }
    }

    private companion object {
        const val PREFS = "encryption-status"

        /** The banner comes back after 7 days while the phone is unverified (spec state bannerDismissed). */
        const val BANNER_PAUSE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Spec recovery-key-enter (bipe-shared product/screens/recovery-key-enter.json). */
class RecoveryKeyEnterModel(private val status: EncryptionStatusModel, private val scope: CoroutineScope) {
    enum class Phase { EDITING, CHECKING, WRONG_KEY, DONE, FAILED }

    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key.asStateFlow()
    private val _phase = MutableStateFlow(Phase.EDITING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Off while the key is blank or a check runs. */
    fun canSubmit(key: String, phase: Phase): Boolean = key.isNotBlank() && phase != Phase.CHECKING && phase != Phase.DONE

    /** Action editKey. */
    fun editKey(text: String) {
        _key.value = text
        if (_phase.value == Phase.WRONG_KEY || _phase.value == Phase.FAILED) _phase.value = Phase.EDITING
    }

    /** Action submit: Encryption.recover. Spaces and line breaks are ignored; the key is never logged. */
    fun submit() {
        if (!canSubmit(_key.value, _phase.value)) return
        _phase.value = Phase.CHECKING
        val recoveryKey = _key.value.filterNot { it.isWhitespace() }
        scope.launch { _phase.value = status.recover(recoveryKey) }
    }
}
