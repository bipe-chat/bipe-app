// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import java.util.Base64

/**
 * The cipher wrapper around the fork's vault file ([BipeForkVault], bipe-chat/bipe#603). The fork writes the file as
 * Base64 text of an AES-GCM result under its Keystore key (fork `libraries/bipe/.../account/BipeAccountVault.kt`, Bipe
 * code); how that result lays out the IV and the cipher text is not Bipe code, so this app does not assume it.
 *
 * - [probe] tries each candidate [Layout] for the rehearsal report. AES-GCM authenticates: a wrong layout never
 *   decrypts, so the report names the one layout that does, without showing a byte of the plain text.
 * - [read] imports only with [CONFIRMED], which stays `null` until the rehearsal on a real phone names the layout
 *   (plan section 4 step 3). Until then the fork's file stays as it is and account-start shows legacyKept.
 */
object BipeForkVaultCipher {
    /** Candidate layouts of the decoded bytes. */
    enum class Layout(val id: String) {
        /** 12-byte IV, then the cipher text with its tag. */
        IV12_PREFIX("iv12-prefix"),

        /** One byte with the IV length, the IV, then the cipher text. */
        IV_LENGTH_BYTE("iv-length-byte"),

        /** Four bytes (big endian) with the IV length, the IV, then the cipher text. */
        IV_LENGTH_INT("iv-length-int"),
    }

    /** The layout the rehearsal showed (bipe-chat/bipe#603). `null`: no import yet. */
    val CONFIRMED: Layout? = null

    /** An IV and the cipher text with its tag. */
    class Sealed(val iv: ByteArray, val body: ByteArray) {
        override fun toString(): String = "Sealed(${iv.size}, ${body.size})"
    }

    /** What the rehearsal report shows about the fork's file. No byte of the IV, the cipher text or the plain text. */
    data class Probe(
        val textLength: Int,
        val base64: Boolean,
        val byteCount: Int,
        /** The candidate that decrypted, or `null`. */
        val layout: Layout?,
        /** The plain text as BipeForkVault JSON: account count and which other parts are there. */
        val accounts: Int?,
        val hasClaim: Boolean?,
        val hasPendingSignup: Boolean?,
        val pendingInviters: Int?,
        /** The JSON key names and value types of the plain text ([BipeLegacyShape.describe]). */
        val shape: String?,
    )

    /** Base64 text (standard or URL-safe, with or without line breaks) to bytes; `null` when it is not Base64. */
    fun decode(text: String): ByteArray? {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull()
    }

    /** The IV and cipher text in [bytes] for [layout]; `null` when the bytes cannot have that layout. */
    fun split(layout: Layout, bytes: ByteArray): Sealed? {
        val (ivStart, ivLength) = when (layout) {
            Layout.IV12_PREFIX -> 0 to 12
            Layout.IV_LENGTH_BYTE -> 1 to (bytes.firstOrNull()?.toInt()?.and(0xFF) ?: return null)
            Layout.IV_LENGTH_INT -> {
                if (bytes.size < 4) return null
                4 to ((bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or
                    (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF))
            }
        }
        // GCM IVs are 12 to 16 bytes in practice; the body holds at least the 16-byte tag.
        if (ivLength !in 12..16 || bytes.size < ivStart + ivLength + TAG_BYTES) return null
        return Sealed(bytes.copyOfRange(ivStart, ivStart + ivLength), bytes.copyOfRange(ivStart + ivLength, bytes.size))
    }

    /**
     * Tries every layout. [open] decrypts with the fork's key and returns the plain text, or `null` (or throws) when the
     * tag does not check.
     */
    fun probe(text: String, open: (Sealed) -> ByteArray?): Probe {
        val bytes = decode(text)
        var found: Layout? = null
        var plain: String? = null
        if (bytes != null) {
            for (layout in Layout.entries) {
                val sealed = split(layout, bytes) ?: continue
                val result = runCatching { open(sealed) }.getOrNull() ?: continue
                found = layout
                plain = result.decodeToString()
                break
            }
        }
        val data = plain?.let(BipeForkVault::parse)
        return Probe(
            textLength = text.length,
            base64 = bytes != null,
            byteCount = bytes?.size ?: 0,
            layout = found,
            accounts = data?.accounts?.size,
            hasClaim = data?.let { it.pendingRecovery != null },
            hasPendingSignup = data?.let { it.pendingSignup != null },
            pendingInviters = data?.pendingInviters?.size,
            shape = plain?.let { BipeLegacyShape.describe(it).toString() },
        )
    }

    /** The fork's vault with the [CONFIRMED] layout, or `null` (no confirmed layout, not readable, not the fork's JSON). */
    fun read(text: String, open: (Sealed) -> ByteArray?, layout: Layout? = CONFIRMED): BipeVaultData? {
        layout ?: return null
        val sealed = decode(text)?.let { split(layout, it) } ?: return null
        val plain = runCatching { open(sealed) }.getOrNull() ?: return null
        return BipeForkVault.parse(plain.decodeToString())
    }

    private const val TAG_BYTES = 16
}
