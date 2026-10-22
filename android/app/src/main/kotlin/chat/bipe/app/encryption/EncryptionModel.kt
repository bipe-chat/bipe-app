// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.OwnerCheck
import chat.bipe.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.BackupStateListener
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener

/**
 * Spec encryption-status (bipe-shared product/screens/encryption-status.json): follows the SDK verification, recovery
 * and backup state, and shows the history banner and sheet when this phone cannot read old messages.
 */
class EncryptionModel(context: Context, val session: AccountSession, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("bipe.encryption", Context.MODE_PRIVATE)
    private val dismissedKey = "history.dismissedAt.${session.userId}"
    private val promptKey = "recoveryKeyPrompt.dismissed.${session.userId}"

    private val _verificationState = MutableStateFlow(VerificationState.UNKNOWN)
    val verificationState: StateFlow<VerificationState> = _verificationState.asStateFlow()
    private val _recoveryState = MutableStateFlow(RecoveryState.UNKNOWN)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    private val _backupState = MutableStateFlow(BackupState.UNKNOWN)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()
    private val _hasOtherDevices = MutableStateFlow(false)
    val hasOtherDevices: StateFlow<Boolean> = _hasOtherDevices.asStateFlow()
    private val _bannerDismissed = MutableStateFlow(
        System.currentTimeMillis() - preferences.getLong(dismissedKey, 0L) < BANNER_PAUSE_MILLIS,
    )
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()
    private val _keyPromptDismissed = MutableStateFlow(preferences.getBoolean(promptKey, false))
    val keyPromptDismissed: StateFlow<Boolean> = _keyPromptDismissed.asStateFlow()

    val sheetOpen = MutableStateFlow(false)
    /** Screens the encryption-status rows open. */
    val showsKeyEnter = MutableStateFlow(false)
    val showsVerification = MutableStateFlow(false)
    val showsKeySave = MutableStateFlow(false)

    private val handles = mutableListOf<TaskHandle>()

    val encryption: Encryption get() = session.client.encryption()

    /** Action observe. */
    fun observe() {
        if (handles.isNotEmpty()) return
        val encryption = encryption
        _verificationState.value = encryption.verificationState()
        _recoveryState.value = encryption.recoveryState()
        _backupState.value = encryption.backupState()
        handles += encryption.verificationStateListener(
            object : VerificationStateListener {
                override fun onUpdate(status: VerificationState) {
                    _verificationState.value = status
                }
            },
        )
        handles += encryption.recoveryStateListener(
            object : RecoveryStateListener {
                override fun onUpdate(status: RecoveryState) {
                    _recoveryState.value = status
                }
            },
        )
        handles += encryption.backupStateListener(
            object : BackupStateListener {
                override fun onUpdate(status: BackupState) {
                    _backupState.value = status
                }
            },
        )
        scope.launch { _hasOtherDevices.value = runCatching { encryption.hasDevicesToVerifyAgainst() }.getOrDefault(false) }
    }

    fun close() {
        handles.forEach { it.cancel() }
        handles.clear()
    }

    /** The banner shows only when this phone is unverified and the account has history elsewhere. */
    fun showsBanner(verification: VerificationState, recovery: RecoveryState, otherDevices: Boolean, dismissed: Boolean): Boolean =
        verification == VerificationState.UNVERIFIED && hasHistoryElsewhere(recovery, otherDevices) && !dismissed

    fun hasHistoryElsewhere(recovery: RecoveryState, otherDevices: Boolean): Boolean = otherDevices || canUseRecoveryKey(recovery)

    fun canUseRecoveryKey(recovery: RecoveryState): Boolean = recovery == RecoveryState.ENABLED || recovery == RecoveryState.INCOMPLETE

    /** recovery-key-save prompt above the chat list: an account with no recovery key, until "Agora não". */
    fun showsKeyPrompt(verification: VerificationState, recovery: RecoveryState, dismissed: Boolean): Boolean =
        recovery == RecoveryState.DISABLED && verification == VerificationState.VERIFIED && !dismissed

    /** Action openSheet. */
    fun openSheet() {
        sheetOpen.value = true
    }

    /** Action approveWithDevice. */
    fun approveWithDevice() {
        sheetOpen.value = false
        showsVerification.value = true
    }

    /** Action useRecoveryKey. */
    fun useRecoveryKey() {
        sheetOpen.value = false
        showsKeyEnter.value = true
    }

    /** Action dismiss. The banner comes back after 7 days while unverified. */
    fun dismiss() {
        _bannerDismissed.value = true
        preferences.edit().putLong(dismissedKey, System.currentTimeMillis()).apply()
    }

    fun dismissKeyPrompt() {
        _keyPromptDismissed.value = true
        preferences.edit().putBoolean(promptKey, true).apply()
    }

    private companion object {
        const val BANNER_PAUSE_MILLIS = 7L * 24 * 3600 * 1000
    }
}

/** Spec recovery-key-save (bipe-shared product/screens/recovery-key-save.json). */
class RecoveryKeySaveModel(private val context: Context, private val encryption: Encryption, private val scope: CoroutineScope) {
    enum class Phase { INTRO, CREATING, SHOW_KEY, SAVED, FAILED }

    enum class Progress { STARTING, CREATING_BACKUP, CREATING_RECOVERY_KEY, BACKING_UP, ROOM_KEY_UPLOAD_ERROR, DONE }

    private val _phase = MutableStateFlow(Phase.INTRO)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _progress = MutableStateFlow(Progress.STARTING)
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    /** Held in memory while showKey lasts. Never logged, never stored by the app. */
    private val _recoveryKey = MutableStateFlow<String?>(null)
    val recoveryKey: StateFlow<String?> = _recoveryKey.asStateFlow()
    private val _copied = MutableStateFlow(false)
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    val recoveryState: RecoveryState get() = encryption.recoveryState()

    val replacing: Boolean get() = recoveryState == RecoveryState.ENABLED || recoveryState == RecoveryState.INCOMPLETE

    /** Action create: a new key, or after the owner check a replacement. [activity] shows the check. */
    fun create(activity: Context) {
        scope.launch {
            val state = encryption.recoveryState()
            val replace = state == RecoveryState.ENABLED || state == RecoveryState.INCOMPLETE
            if (replace && !OwnerCheck.confirm(activity, context.getString(R.string.bipe_app_recovery_key_biometric))) return@launch
            _phase.value = Phase.CREATING
            try {
                val key = if (!replace) {
                    encryption.enableRecovery(
                        waitForBackupsToUpload = true,
                        passphrase = null,
                        progressListener = object : EnableRecoveryProgressListener {
                            override fun onUpdate(status: EnableRecoveryProgress) {
                                _progress.value = when (status) {
                                    is EnableRecoveryProgress.Starting -> Progress.STARTING
                                    is EnableRecoveryProgress.CreatingBackup -> Progress.CREATING_BACKUP
                                    is EnableRecoveryProgress.CreatingRecoveryKey -> Progress.CREATING_RECOVERY_KEY
                                    is EnableRecoveryProgress.BackingUp -> Progress.BACKING_UP
                                    is EnableRecoveryProgress.RoomKeyUploadError -> Progress.ROOM_KEY_UPLOAD_ERROR
                                    is EnableRecoveryProgress.Done -> Progress.DONE
                                }
                            }
                        },
                    )
                } else {
                    encryption.resetRecoveryKey()
                }
                _recoveryKey.value = key
                _phase.value = Phase.SHOW_KEY
            } catch (error: Exception) {
                _phase.value = Phase.FAILED
            }
        }
    }

    /** Action copy: marked sensitive so the clipboard preview hides it, cleared after 60 s. */
    fun copy() {
        val key = _recoveryKey.value ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.bipe_account_recovery_key), key)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        _copied.value = true
        scope.launch {
            delay(60_000)
            val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (current == key && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
        }
    }

    /** Action confirmSaved. */
    fun confirmSaved() {
        _recoveryKey.value = null
        _phase.value = Phase.SAVED
    }

    /** The key in groups of 4 characters. */
    fun groupedKey(key: String?): String = key?.filterNot { it.isWhitespace() }?.chunked(4)?.joinToString(" ").orEmpty()
}

/** Spec recovery-key-enter (bipe-shared product/screens/recovery-key-enter.json). */
class RecoveryKeyEnterModel(private val encryption: Encryption, private val scope: CoroutineScope) {
    enum class Phase { EDITING, CHECKING, WRONG_KEY, DONE, FAILED }

    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key.asStateFlow()
    private val _phase = MutableStateFlow(Phase.EDITING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Action editKey. */
    fun editKey(text: String) {
        _key.value = text
        if (_phase.value == Phase.WRONG_KEY || _phase.value == Phase.FAILED) _phase.value = Phase.EDITING
    }

    /** Action submit. Spaces and line breaks are ignored. */
    fun submit() {
        val compact = _key.value.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return
        _phase.value = Phase.CHECKING
        scope.launch {
            try {
                encryption.recover(compact)
                _key.value = ""
                _phase.value = Phase.DONE
            } catch (error: Exception) {
                _phase.value = Phase.WRONG_KEY
            }
        }
    }
}

/**
 * Spec device-verification (bipe-shared product/screens/device-verification.json): Matrix SAS between two devices of
 * the same account. One model per session listens for incoming requests (approver) and runs requests (newPhone).
 */
class DeviceVerificationModel(private val context: Context, private val session: AccountSession, private val scope: CoroutineScope) {
    enum class Role { NEW_PHONE, APPROVER }

    enum class Phase { IDLE, REQUESTING, WAITING_FOR_OTHER, INCOMING, CONFIRM_IDENTITY, COMPARE, WAITING_FOR_MATCH, DONE, CANCELLED, MISMATCH, FAILED }

    data class Symbol(val symbol: String, val label: String)

    private val _role = MutableStateFlow(Role.NEW_PHONE)
    val role: StateFlow<Role> = _role.asStateFlow()
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _otherDevice = MutableStateFlow<SessionVerificationRequestDetails?>(null)
    val otherDevice: StateFlow<SessionVerificationRequestDetails?> = _otherDevice.asStateFlow()
    private val _symbols = MutableStateFlow<List<Symbol>>(emptyList())
    val symbols: StateFlow<List<Symbol>> = _symbols.asStateFlow()

    private var controller: SessionVerificationController? = null

    /** Starts listening for requests from the account's other devices. */
    fun start() {
        if (controller != null) return
        scope.launch {
            val created = runCatching { session.client.getSessionVerificationController() }.getOrNull() ?: return@launch
            controller = created
            created.setDelegate(
                object : SessionVerificationControllerDelegate {
                    override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
                        scope.launch { onRequest(details) }
                    }

                    override fun didAcceptVerificationRequest() {
                        // The new phone starts SAS when the accept arrives.
                        if (_role.value == Role.NEW_PHONE) scope.launch { runCatching { controller?.startSasVerification() } }
                    }

                    override fun didStartSasVerification() = Unit

                    override fun didReceiveVerificationData(data: SessionVerificationData) {
                        _symbols.value = when (data) {
                            is SessionVerificationData.Emojis -> data.emojis.map { Symbol(it.symbol(), it.description()) }
                            is SessionVerificationData.Decimals -> data.values.map { Symbol(it.toString(), "") }
                        }
                        _phase.value = Phase.COMPARE
                    }

                    override fun didFail() {
                        _phase.value = Phase.FAILED
                    }

                    override fun didCancel() {
                        if (_phase.value != Phase.MISMATCH) _phase.value = Phase.CANCELLED
                    }

                    override fun didFinish() {
                        _phase.value = Phase.DONE
                    }
                },
            )
        }
    }

    private suspend fun onRequest(details: SessionVerificationRequestDetails) {
        if (_phase.value != Phase.IDLE) return
        _role.value = Role.APPROVER
        _otherDevice.value = details
        _phase.value = Phase.INCOMING
        runCatching { controller?.acknowledgeVerificationRequest(details.senderProfile.userId, details.flowId) }
    }

    /** Action request (newPhone). */
    fun request() {
        _role.value = Role.NEW_PHONE
        _phase.value = Phase.REQUESTING
        scope.launch {
            _phase.value = runCatching { controller?.requestDeviceVerification() }
                .fold(onSuccess = { Phase.WAITING_FOR_OTHER }, onFailure = { Phase.FAILED })
        }
    }

    /** Action accept (approver): after the owner check. [activity] shows the check. */
    fun accept(activity: Context) {
        _phase.value = Phase.CONFIRM_IDENTITY
        scope.launch {
            if (!OwnerCheck.confirm(activity, context.getString(R.string.bipe_app_verify_biometric))) {
                _phase.value = Phase.INCOMING
                return@launch
            }
            _phase.value = runCatching { controller?.acceptVerificationRequest() }
                .fold(onSuccess = { Phase.WAITING_FOR_OTHER }, onFailure = { Phase.FAILED })
        }
    }

    /** Action decline (approver). */
    fun decline() {
        scope.launch { runCatching { controller?.declineVerification() } }
        reset()
    }

    /** Action match: the symbols are the same. */
    fun match() {
        _phase.value = Phase.WAITING_FOR_MATCH
        scope.launch {
            runCatching { controller?.approveVerification() }.onFailure { _phase.value = Phase.FAILED }
        }
    }

    /** Action noMatch: nothing is approved. */
    fun noMatch() {
        _phase.value = Phase.MISMATCH
        scope.launch { runCatching { controller?.declineVerification() } }
    }

    /** Action cancel. */
    fun cancel() {
        scope.launch { runCatching { controller?.cancelVerification() } }
        reset()
    }

    fun reset() {
        _phase.value = Phase.IDLE
        _symbols.value = emptyList()
        _otherDevice.value = null
    }
}
