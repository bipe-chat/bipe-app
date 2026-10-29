// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeGovbrReason
import chat.bipe.shared.api.sendGovbrDocument
import chat.bipe.shared.api.startGovbrVerification
import chat.bipe.shared.api.unverifiedName
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trust step 5 (spec govbr-verify, bipe-chat/bipe#544): the user signs a Bipe challenge PDF in the gov.br app. The
 * server makes the PDF and checks the signed file. The app saves the PDF, sends the user to gov.br and uploads the file
 * that comes back. Same phases as BipeKit BipeGovbrFlow.swift.
 */
class BipeGovbrFlow(private val api: BipeApiClient, private val accessToken: suspend () -> String?) {
    sealed interface Phase {
        data object Ready : Phase
        data object Preparing : Phase

        /** The user saves [pdf] and signs it in the gov.br app. */
        class WaitingForSignature(val pdf: ByteArray, val fileName: String) : Phase
        data object Checking : Phase

        /** [name] is the verified name from the new badge, when the server sent one. */
        data class Verified(val name: String?) : Phase
        data class Rejected(val reason: BipeGovbrReason) : Phase
        data object Failed : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Ready)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private var verificationId: String? = null

    /** Gets a new challenge. Also the "Tentar de novo" action: a new challenge is always valid. */
    suspend fun start() {
        _phase.value = Phase.Preparing
        try {
            val challenge = BipeApiClient.withTokenRetry(accessToken) { api.startGovbrVerification(it) }
            val pdf = challenge.pdfBytes
            if (pdf.isEmpty()) {
                _phase.value = Phase.Failed
                return
            }
            verificationId = challenge.verificationId
            _phase.value = Phase.WaitingForSignature(pdf, challenge.fileName)
        } catch (error: BipeApiException) {
            _phase.value = Phase.Failed
        }
    }

    /** Uploads the signed file: from the share sheet or from the "Já assinei" picker. Null is a file that did not read. */
    suspend fun submit(pdf: ByteArray?) {
        val id = verificationId ?: run {
            _phase.value = Phase.Failed
            return
        }
        if (pdf == null || pdf.isEmpty() || pdf.size > BipeRegistries.GOVBR_MAX_DOCUMENT_BYTES) {
            _phase.value = Phase.Rejected(BipeGovbrReason.NOT_SIGNED)
            return
        }
        _phase.value = Phase.Checking
        _phase.value = try {
            val status = BipeApiClient.withTokenRetry(accessToken) { api.sendGovbrDocument(id, pdf, it) }
            when (status.state) {
                "verified" -> Phase.Verified(status.badge?.unverifiedName)
                "rejected", "expired" -> Phase.Rejected(BipeGovbrReason.fromId(status.reason) ?: BipeGovbrReason.EXPIRED)
                else -> Phase.Failed
            }
        } catch (error: BipeApiException) {
            Phase.Failed
        }
    }

    /** true while a challenge waits for its signed file. */
    val isWaiting: Boolean get() = _phase.value is Phase.WaitingForSignature
}
