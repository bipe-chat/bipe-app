// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import android.util.Base64
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.QrLogin
import chat.bipe.app.account.SessionStore
import chat.bipe.app.linkcomputer.canCopyQrCode
import chat.bipe.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.HumanQrLoginException
import org.matrix.rustcomponents.sdk.QrCodeData
import org.matrix.rustcomponents.sdk.QrLoginProgress
import org.matrix.rustcomponents.sdk.QrLoginProgressListener
import org.matrix.rustcomponents.sdk.Session
import uniffi.matrix_sdk_crypto.QrCodeIntent

/**
 * Spec recovery-old-phone (bipe-shared product/screens): this new phone scans the code that the old phone shows on
 * link-phone-show (ADR 0059), shows the check code, waits for the approval on the old phone and signs in through MAS.
 * Cross-signing and the backup key arrive with the session, so no PIN and no identity screen follow.
 */
class OldPhoneModel(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    private val onSignedIn: (AccountSession) -> Unit,
) {
    enum class Phase { CAMERA_DENIED, SCANNING, CONNECTING, SHOW_CHECK_CODE, WAITING_FOR_APPROVAL, SYNCING, FAILED }

    enum class Failure(val message: Int) {
        NOT_BIPE_CODE(R.string.bipe_app_old_phone_not_bipe_code),
        EXPIRED(R.string.bipe_app_old_phone_expired),
        INSECURE(R.string.bipe_app_old_phone_insecure),
        DECLINED(R.string.bipe_app_old_phone_declined),
        NOT_SUPPORTED(R.string.bipe_app_old_phone_not_supported),
        STOPPED(R.string.bipe_app_old_phone_stopped),
        GENERIC(R.string.bipe_error_generic),
    }

    data class State(
        val phase: Phase = Phase.SCANNING,
        val checkCode: String? = null,
        val error: Failure? = null,
        val pasted: String = "",
    )

    /** Debug builds and the emulator only: the paste field next to the camera (bipe-chat/bipe#608). */
    val canPaste: Boolean = canCopyQrCode(context)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    /** Each try has its own number, so that a stopped try never touches the next one. */
    private var attempt = 0

    /** Action open: the camera permission result. Without it, the paste field still works where canPaste. */
    fun cameraResult(allowed: Boolean) {
        if (_state.value.phase == Phase.SCANNING || _state.value.phase == Phase.CAMERA_DENIED) {
            _state.update { it.copy(phase = if (allowed) Phase.SCANNING else Phase.CAMERA_DENIED) }
        }
    }

    /** Action codeRead: only a code that a signed-in device shows (QrCodeIntent.RECIPROCATE) starts the login. */
    fun codeRead(bytes: ByteArray) {
        val phase = _state.value.phase
        if (job != null || (phase != Phase.SCANNING && phase != Phase.FAILED && phase != Phase.CAMERA_DENIED)) return
        val data = runCatching { QrCodeData.fromBytes(bytes) }.getOrNull()
        if (data == null || data.intent() != QrCodeIntent.RECIPROCATE) {
            fail(Failure.NOT_BIPE_CODE)
            return
        }
        attempt += 1
        val current = attempt
        _state.update { it.copy(phase = Phase.CONNECTING, error = null, checkCode = null) }
        val listener = object : QrLoginProgressListener {
            override fun onUpdate(state: QrLoginProgress) {
                scope.launch { if (attempt == current) progress(state) }
            }
        }
        job = scope.launch {
            try {
                val session = login(data, listener)
                // The login client is gone here: the store can move.
                val account = QrLogin.adopt(context, session, sessionStore)
                job = null
                onSignedIn(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt != current) return@launch
                AccountSession.deleteStoreFolder(context, QrLogin.FOLDER)
                taskFailed(e)
            }
        }
    }

    /** LoginWithQrCodeHandler.scan in a fresh store. The client lives only in this call. */
    private suspend fun login(data: QrCodeData, listener: QrLoginProgressListener): Session {
        val client = QrLogin.makeClient(context, data.serverName(), sessionStore)
        try {
            val handler = client.newLoginWithQrCodeHandler(QrLogin.oauthConfiguration)
            try {
                handler.scan(data, listener)
            } finally {
                handler.destroy()
            }
            return client.session()
        } finally {
            client.destroy()
        }
    }

    fun editPasted(text: String) {
        if (canPaste) _state.update { it.copy(pasted = text) }
    }

    /** Action submitPasted: base64 of the code bytes, as link-phone-show copies it. */
    fun submitPasted() {
        if (!canPaste) return
        val text = _state.value.pasted.filterNot { it.isWhitespace() }
        val bytes = runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            fail(Failure.NOT_BIPE_CODE)
            return
        }
        codeRead(bytes)
    }

    /** Action progress. */
    private fun progress(update: QrLoginProgress) {
        if (job == null) return
        when (update) {
            QrLoginProgress.Starting -> Unit
            is QrLoginProgress.EstablishingSecureChannel ->
                _state.update { it.copy(checkCode = update.checkCodeString, phase = Phase.SHOW_CHECK_CODE) }
            is QrLoginProgress.WaitingForToken -> _state.update { it.copy(phase = Phase.WAITING_FOR_APPROVAL) }
            QrLoginProgress.SyncingSecrets, QrLoginProgress.Done -> _state.update { it.copy(phase = Phase.SYNCING) }
        }
    }

    /** Action taskFailed: maps the SDK error to a string. */
    private fun taskFailed(error: Exception) {
        job = null
        fail(
            when (error) {
                is HumanQrLoginException.NotFound, is HumanQrLoginException.Expired -> Failure.EXPIRED
                is HumanQrLoginException.ConnectionInsecure,
                is HumanQrLoginException.CheckCodeAlreadySent,
                is HumanQrLoginException.CheckCodeCannotBeSent,
                -> Failure.INSECURE
                is HumanQrLoginException.Declined -> Failure.DECLINED
                is HumanQrLoginException.Cancelled -> Failure.STOPPED
                is HumanQrLoginException.LinkingNotSupported,
                is HumanQrLoginException.SlidingSyncNotAvailable,
                is HumanQrLoginException.OAuthMetadataInvalid,
                -> Failure.NOT_SUPPORTED
                is HumanQrLoginException.UnsupportedQrCodeType, is HumanQrLoginException.OtherDeviceNotSignedIn -> Failure.NOT_BIPE_CODE
                else -> Failure.GENERIC
            },
        )
    }

    private fun fail(failure: Failure) {
        _state.update { it.copy(phase = Phase.FAILED, error = failure, checkCode = null) }
    }

    /** Action scanAgain. */
    fun scanAgain() {
        attempt += 1
        job?.cancel()
        job = null
        _state.update { it.copy(phase = Phase.SCANNING, error = null, checkCode = null, pasted = "") }
    }

    /** Action cancel: back to recovery-start. The next try deletes the fresh store. */
    fun cancel() {
        attempt += 1
        job?.cancel()
        job = null
    }
}
