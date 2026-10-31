// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeVerificationStatus
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trust step 5 (bipe-chat/bipe#544, spec govbr-verify, bipe docs/identity.md): the user signs a Bipe challenge PDF in the
 * gov.br app. The server makes the PDF and checks the signed file. The app saves the PDF, sends the user to gov.br and
 * uploads the file that comes back. Same phases and rules as BipeKit `BipeGovbrFlow` on iOS; shared rules: [BipeGovbrRules].
 */
class BipeGovbrFlow(private val api: BipeApiClient, private val accessToken: suspend () -> String?) {
    sealed interface Phase {
        /** The phase name of the spec and the test vectors. */
        val id: String

        data object Ready : Phase { override val id = "ready" }
        data object Preparing : Phase { override val id = "preparing" }

        /** The user saves [pdf] and signs it in the gov.br app. */
        class WaitingForSignature(val pdf: ByteArray, val fileName: String) : Phase { override val id = "waitingForSignature" }
        data object Checking : Phase { override val id = "checking" }

        /** [name] is the verified name from the new badge, when the server sent one. */
        data class Verified(val name: String?) : Phase { override val id = "verified" }
        data class Rejected(val reason: BipeGovbrReason) : Phase { override val id = "rejected" }
        data object Failed : Phase { override val id = "failed" }
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Ready)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The level of the new badge after [Phase.Verified], read without the signature check: only for the owner's trust card. */
    var verifiedLevel: BadgeLevel? = null
        private set

    private val _showsNoOpenVerification = MutableStateFlow(false)

    /** A signed file came while no check was open (rule noOpenVerification): the screen shows bipe_govbr_no_open_verification. */
    val showsNoOpenVerification: StateFlow<Boolean> = _showsNoOpenVerification.asStateFlow()

    private var verificationId: String? = null

    /** Gets a new challenge. Also the "Tentar de novo" action: a new challenge is always valid. */
    suspend fun start() {
        _phase.value = Phase.Preparing
        _showsNoOpenVerification.value = false
        verificationId = null
        _phase.value = try {
            val challenge = BipeApiClient.withTokenRetry(accessToken) { api.startGovbrVerification(it) }
            val pdf = java.util.Base64.getDecoder().decode(challenge.pdf)
            verificationId = challenge.verificationId
            Phase.WaitingForSignature(pdf, challenge.fileName)
        } catch (_: BipeApiException) {
            Phase.Failed
        } catch (_: IllegalArgumentException) {
            Phase.Failed
        }
    }

    /**
     * A signed file from the share sheet, open with, or the "Já assinei" picker (spec govbr-verify, action receiveDocument).
     * Uploads it only when the rule says upload. Returns the rule's answer.
     */
    suspend fun receive(pdf: ByteArray): BipeGovbrRules.Incoming {
        val incoming = BipeGovbrRules.incoming(_phase.value.id, pdf.size)
        when (incoming) {
            BipeGovbrRules.Incoming.IGNORE -> return incoming
            BipeGovbrRules.Incoming.NO_OPEN_VERIFICATION -> {
                _showsNoOpenVerification.value = true
                return incoming
            }
            BipeGovbrRules.Incoming.NOT_SIGNED -> {
                _phase.value = Phase.Rejected(BipeGovbrReason.NOT_SIGNED)
                return incoming
            }
            BipeGovbrRules.Incoming.UPLOAD -> {}
        }
        val id = verificationId
        if (id == null) {
            _phase.value = Phase.Failed
            return incoming
        }
        _showsNoOpenVerification.value = false
        _phase.value = Phase.Checking
        _phase.value = try {
            val status = BipeApiClient.withTokenRetry(accessToken) { api.sendGovbrDocument(id, pdf, it) }
            when (val outcome = BipeGovbrRules.outcome(status.state, status.reason)) {
                BipeGovbrRules.Outcome.Verified -> {
                    val payload = status.badge?.let(::unverifiedPayload)
                    verifiedLevel = payload?.lvl?.let(BipeGovbrRules::badgeLevel)
                    Phase.Verified(payload?.name)
                }
                is BipeGovbrRules.Outcome.Rejected -> Phase.Rejected(outcome.reason)
                BipeGovbrRules.Outcome.Failed -> Phase.Failed
            }
        } catch (_: BipeApiException) {
            Phase.Failed
        }
        return incoming
    }

    companion object {
        /** The API's limit for the signed file (registries/govbr.json). */
        const val MAX_DOCUMENT_BYTES = BipeRegistries.GOVBR_MAX_DOCUMENT_BYTES

        private val looseJson = Json { ignoreUnknownKeys = true }

        /**
         * The `name` and `lvl` of a badge envelope, read without the signature check. Only for the owner's own screen and
         * trust card, never to show a seal.
         */
        fun unverifiedPayload(envelope: BadgeEnvelope): LoosePayload? {
            val bytes = Base64Url.decode(envelope.payload.trimEnd('=')) ?: return null
            return runCatching { looseJson.decodeFromString(LoosePayload.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
        }

        /** The level of a `GET /v1/badges/{userId}` 200 answer, read without the signature check (see [unverifiedPayload]). */
        fun unverifiedLevel(envelopeJson: String): BadgeLevel? {
            val envelope = runCatching { looseJson.decodeFromString(BadgeEnvelope.serializer(), envelopeJson) }.getOrNull() ?: return null
            return unverifiedPayload(envelope)?.lvl?.let(BipeGovbrRules::badgeLevel)
        }
    }

    @Serializable
    data class LoosePayload(val name: String? = null, val lvl: String? = null)
}

/** `VerificationStatus.reason`. */
enum class BipeGovbrReason(val id: String) {
    NOT_SIGNED("not_signed"),
    NOT_GOVBR("not_govbr"),
    WRONG_DOCUMENT("wrong_document"),
    EXPIRED("expired"),
    CPF_IN_USE("cpf_in_use"),
    CHANGED_AFTER_SIGNING("changed_after_signing"),
    ;

    companion object {
        fun fromId(id: String): BipeGovbrReason? = entries.firstOrNull { it.id == id }
    }
}

/** The rules both apps follow (bipe-shared tools/lib/govbr.mjs, test-vectors/govbr-verify.json). */
object BipeGovbrRules {
    /** What the app does with a PDF that arrives. */
    enum class Incoming(val id: String) {
        /** Send it with `PUT .../document`. */
        UPLOAD("upload"),

        /** Show bipe_govbr_no_open_verification. Nothing is uploaded. */
        NO_OPEN_VERIFICATION("noOpenVerification"),

        /** A check runs: drop the file without a message. */
        IGNORE("ignore"),

        /** Empty or too big: shown as the reason not_signed, without an upload. */
        NOT_SIGNED("notSigned"),
    }

    /** The phase after a `VerificationStatus` answer. */
    sealed interface Outcome {
        data object Verified : Outcome
        data class Rejected(val reason: BipeGovbrReason) : Outcome
        data object Failed : Outcome
    }

    /** [phase] is a [BipeGovbrFlow.Phase.id]. */
    fun incoming(phase: String, byteCount: Int, maxDocumentBytes: Int = BipeRegistries.GOVBR_MAX_DOCUMENT_BYTES): Incoming = when {
        phase == "checking" -> Incoming.IGNORE
        phase != "waitingForSignature" -> Incoming.NO_OPEN_VERIFICATION
        byteCount <= 0 || byteCount > maxDocumentBytes -> Incoming.NOT_SIGNED
        else -> Incoming.UPLOAD
    }

    /** A rejection without a reason, and the state expired, is expired. A reason this build does not know fails closed. */
    fun outcome(state: String, reason: String?): Outcome = when (state) {
        "verified" -> Outcome.Verified
        "rejected" -> if (reason == null) Outcome.Rejected(BipeGovbrReason.EXPIRED)
            else BipeGovbrReason.fromId(reason)?.let { Outcome.Rejected(it) } ?: Outcome.Failed
        "expired" -> Outcome.Rejected(BipeGovbrReason.EXPIRED)
        else -> Outcome.Failed
    }

    internal fun badgeLevel(id: String): BadgeLevel? = when (id) {
        "pix" -> BadgeLevel.PIX
        "govbr" -> BadgeLevel.GOVBR
        "institution" -> BadgeLevel.INSTITUTION
        "cnpj" -> BadgeLevel.CNPJ
        "brand-mark" -> BadgeLevel.BRAND_MARK
        else -> null
    }
}
