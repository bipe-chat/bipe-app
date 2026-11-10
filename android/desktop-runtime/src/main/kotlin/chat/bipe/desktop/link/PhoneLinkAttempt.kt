// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.storage.AccountRepository
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PhoneLinkPhase {
    data object Idle : PhoneLinkPhase
    data object Starting : PhoneLinkPhase
    class Qr(val payload: LinkQrPayload) : PhoneLinkPhase
    class CheckCode internal constructor(internal val challenge: CheckCodeChallenge, val invalidInput: Boolean = false) : PhoneLinkPhase
    data object SubmittingCheckCode : PhoneLinkPhase
    data object Approving : PhoneLinkPhase
    data object ImportingKeys : PhoneLinkPhase
    data object Linked : PhoneLinkPhase
    data object Cancelled : PhoneLinkPhase
    data class Failed(val reason: PhoneLinkFailure) : PhoneLinkPhase
}

/** One attempt belongs to one pending account. Retry creates another attempt after explicit old-slot cleanup. */
class PhoneLinkAttempt internal constructor(
    private val owner: AccountLifetime,
    private val protocol: PhoneLinkProtocol,
    private val commit: suspend () -> Unit,
) {
    private val monitor = Any()
    private val mutablePhase = MutableStateFlow<PhoneLinkPhase>(PhoneLinkPhase.Idle)
    val phase = mutablePhase.asStateFlow()

    /** Returns null on a second tap. The UI observes phase; it does not own the native task. */
    fun start(): Job? {
        var job: Job? = null
        owner.publish(owner.epoch) { synchronized(monitor) {
            if (mutablePhase.value != PhoneLinkPhase.Idle) return@synchronized
            mutablePhase.value = PhoneLinkPhase.Starting
            job = owner.launch {
                try {
                    protocol.generate(::progress)
                    owner.requireCurrent()
                    commit()
                    owner.publish(owner.epoch) { synchronized(monitor) {
                        if (!terminal(mutablePhase.value)) mutablePhase.value = PhoneLinkPhase.Linked
                    } }
                } catch (error: CancellationException) {
                    if (cancelled()) owner.closeAsync()
                    throw error
                } catch (error: Exception) {
                    failed(reason(error))
                } finally { protocol.close() }
            }
        } }
        return job
    }

    fun submitCheckCode(digits: String): Job? {
        var job: Job? = null
        owner.publish(owner.epoch) { synchronized(monitor) {
            val current = mutablePhase.value as? PhoneLinkPhase.CheckCode ?: return@synchronized
            if (digits.length != 2 || digits.any { it !in '0'..'9' }) {
                mutablePhase.value = PhoneLinkPhase.CheckCode(current.challenge, invalidInput = true)
                return@synchronized
            }
            mutablePhase.value = PhoneLinkPhase.SubmittingCheckCode
            job = owner.launch {
                try { current.challenge.submit(digits.toInt().toUByte()) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { failed(reason(error)) }
            }
        } }
        return job
    }

    /** Cancels this pending device's entire lifetime. The caller must explicitly discard/revoke its slot. */
    fun cancel() {
        if (cancelled()) owner.closeAsync()
    }

    private fun cancelled() = synchronized(monitor) {
        if (terminal(mutablePhase.value)) false
        else { mutablePhase.value = PhoneLinkPhase.Cancelled; true }
    }

    private fun progress(update: PhoneLinkUpdate) {
        owner.publish(owner.epoch) { synchronized(monitor) {
            val current = mutablePhase.value
            if (terminal(current)) return@synchronized
            mutablePhase.value = when (update) {
                PhoneLinkUpdate.Starting -> current
                is PhoneLinkUpdate.QrReady -> if (current == PhoneLinkPhase.Starting || current is PhoneLinkPhase.Qr)
                    PhoneLinkPhase.Qr(update.payload) else current
                is PhoneLinkUpdate.CheckRequested -> if (current == PhoneLinkPhase.Starting || current is PhoneLinkPhase.Qr)
                    PhoneLinkPhase.CheckCode(update.challenge) else current
                PhoneLinkUpdate.WaitingForApproval -> if (current != PhoneLinkPhase.ImportingKeys)
                    PhoneLinkPhase.Approving else current
                PhoneLinkUpdate.ImportingKeys -> PhoneLinkPhase.ImportingKeys
            }
        } }
    }

    private fun failed(reason: PhoneLinkFailure) {
        var changed = false
        owner.publish(owner.epoch) { synchronized(monitor) {
            if (!terminal(mutablePhase.value)) {
                mutablePhase.value = PhoneLinkPhase.Failed(reason)
                changed = true
            }
        } }
        // Cleanup cannot await the task that is reporting this failure.
        if (changed) owner.closeAsync()
    }

    private fun reason(error: Exception): PhoneLinkFailure = when (error) {
        is PhoneLinkException -> error.reason
        is StorageFailure -> if (error.code == StorageCode.DUPLICATE_ACCOUNT) PhoneLinkFailure.DUPLICATE_ACCOUNT
                             else PhoneLinkFailure.STORAGE
        else -> PhoneLinkFailure.PROTOCOL // Never expose SDK messages, URLs, OAuth codes or parser causes to UI/logs.
    }

    private fun terminal(phase: PhoneLinkPhase) = phase == PhoneLinkPhase.Linked || phase == PhoneLinkPhase.Cancelled ||
        phase is PhoneLinkPhase.Failed

    companion object {
        suspend fun create(runtime: MatrixAccountRuntime, repository: AccountRepository,
                           registration: WindowsLoginRegistration): PhoneLinkAttempt {
            runtime.claimPhoneLink()
            val protocol = runtime.lifetime.own(SdkPhoneLinkProtocol(runtime, registration))
            return PhoneLinkAttempt(runtime.lifetime, protocol) { runtime.completePhoneLink(repository) }
        }
    }
}
