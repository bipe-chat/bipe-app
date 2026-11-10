// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import androidx.annotation.StringRes
import chat.bipe.core.ScamKind
import chat.bipe.core.scamMatchIds
import chat.bipe.core.scamWarningKind

/** The scam pattern a message looks like (security.md rule 9). Friction, not protection. */
enum class ScamWarningKind(val id: String, @StringRes val messageRes: Int) {
    EXTORTION("extortion", R.string.bipe_scam_warning_extortion),
    PIX_CODE("pixCode", R.string.bipe_scam_warning_pix_code),
    BOLETO("boleto", R.string.bipe_scam_warning_boleto),
    REMOTE_APP("remoteApp", R.string.bipe_scam_warning_remote_app),
    PIN_REQUEST("pinRequest", R.string.bipe_scam_warning_pin_request),
    PIX_KEY("pixKey", R.string.bipe_scam_warning_pix_key),
    DELIVERY_FEE("deliveryFee", R.string.bipe_scam_warning_delivery_fee),
    LOOKALIKE_LINK("lookalikeLink", R.string.bipe_scam_warning_lookalike_link),
    BANK_ALERT("bankAlert", R.string.bipe_scam_warning_bank_alert),
    RECEIPT("receipt", R.string.bipe_scam_warning_receipt),
    NEW_NUMBER("newNumber", R.string.bipe_scam_warning_new_number),
    URGENCY("urgency", R.string.bipe_scam_warning_urgency);

    /** The bipe-core kind of the same name. */
    val coreKind: ScamKind
        get() = when (this) {
            EXTORTION -> ScamKind.EXTORTION
            PIX_CODE -> ScamKind.PIX_CODE
            BOLETO -> ScamKind.BOLETO
            REMOTE_APP -> ScamKind.REMOTE_APP
            PIN_REQUEST -> ScamKind.PIN_REQUEST
            PIX_KEY -> ScamKind.PIX_KEY
            DELIVERY_FEE -> ScamKind.DELIVERY_FEE
            LOOKALIKE_LINK -> ScamKind.LOOKALIKE_LINK
            BANK_ALERT -> ScamKind.BANK_ALERT
            RECEIPT -> ScamKind.RECEIPT
            NEW_NUMBER -> ScamKind.NEW_NUMBER
            URGENCY -> ScamKind.URGENCY
        }

    companion object {
        fun fromId(id: String): ScamWarningKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Flags payment-scam signals. The rules run in bipe-core (`scamMatchIds`, `scamWarningKind`, ADR 0267).
 * Must match bipe-shared/test-vectors/scam-detection.json and scam-warnings.json.
 */
object ScamDetector {
    /** Match IDs in reference order: `<patternGroup>:<id>` for regexes, then `urgency:<phrase>`, then `receipt:<keyword>`. */
    fun matchIds(message: String): List<String> = scamMatchIds(message)

    /**
     * The one warning to show for a message, or `null`. Cached by event ID, so timeline rebuilds stay cheap.
     * The body is part of the key because an edit keeps the event ID.
     */
    fun warningKind(message: String, eventId: String): ScamWarningKind? {
        val bodyHash = message.hashCode()
        synchronized(warningCache) {
            val cached = warningCache[eventId]
            if (cached != null && cached.bodyHash == bodyHash) return cached.kind
        }

        val kind = warningKind(message)
        synchronized(warningCache) {
            if (warningCache.size >= MAX_CACHED_EVENTS) warningCache.clear()
            warningCache[eventId] = CachedWarning(bodyHash, kind)
        }
        return kind
    }

    /** The one warning to show for a message, or `null`. Kinds in [skipping] are passed over. */
    fun warningKind(message: String, skipping: Set<ScamWarningKind> = emptySet()): ScamWarningKind? =
        scamWarningKind(message, skipping.map { it.coreKind }).let { kind -> ScamWarningKind.entries.firstOrNull { it.coreKind == kind } }

    private const val MAX_CACHED_EVENTS = 2000

    private data class CachedWarning(val bodyHash: Int, val kind: ScamWarningKind?)

    private val warningCache = HashMap<String, CachedWarning>()
}
