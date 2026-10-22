// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.ReservedNameCategory
import java.text.Normalizer

// People rules written from the references in bipe-shared tools/lib/registries.mjs (normalize, profileProblem,
// normalizePhone, normalizeCnpj, formatCnpj) and registries/. Cases: test-vectors/normalization.json,
// phone-numbers.json, cnpj.json. Same rules as BipeKit on iOS.

/** Folds a display name for comparison with reserved names (security rule 8). */
object NameNormalizer {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    /** NFKD, strip combining marks, lowercase, fold homoglyphs (registries/homoglyphs.json), collapse whitespace, trim. */
    fun normalize(input: String): String {
        val stripped = marks.replace(Normalizer.normalize(input, Normalizer.Form.NFKD), "").lowercase()
        val folded = buildString {
            var index = 0
            while (index < stripped.length) {
                val codePoint = stripped.codePointAt(index)
                append(BipeRegistries.homoglyphs[codePoint] ?: String(Character.toChars(codePoint)))
                index += Character.charCount(codePoint)
            }
        }
        return spaces.replace(folded, " ").trim()
    }

    /** The category when the name is a reserved name after [normalize] (exact match), else `null`. */
    fun reservedCategory(name: String): ReservedNameCategory? = BipeRegistries.reservedNames[normalize(name)]
}

/** Same ids as `profileProblem()` and the API's `ProfileProblem`. */
enum class BipeProfileProblem(val id: String) {
    NAME_EMPTY("name_empty"),
    NAME_TOO_LONG("name_too_long"),
    NAME_RESERVED("name_reserved"),
    BIO_TOO_LONG("bio_too_long"),
    BIO_TOO_MANY_LINES("bio_too_many_lines"),

    /** Server only: turning the profile on needs a handle. */
    NO_HANDLE("no_handle"),
    ;

    fun localizedMessage(context: Context): String = when (this) {
        NAME_EMPTY -> context.getString(R.string.bipe_public_profile_name_empty)
        NAME_TOO_LONG -> context.getString(R.string.bipe_public_profile_name_too_long, BipeRegistries.PROFILE_NAME_MAX_LENGTH.toString())
        NAME_RESERVED -> context.getString(R.string.bipe_app_profile_name_reserved)
        BIO_TOO_LONG -> context.getString(R.string.bipe_public_profile_bio_too_long, BipeRegistries.PROFILE_BIO_MAX_LENGTH.toString())
        BIO_TOO_MANY_LINES -> context.getString(R.string.bipe_public_profile_bio_too_many_lines, BipeRegistries.PROFILE_BIO_MAX_LINES.toString())
        NO_HANDLE -> context.getString(R.string.bipe_public_profile_needs_handle)
    }

    companion object {
        fun fromId(id: String): BipeProfileProblem? = entries.firstOrNull { it.id == id }
    }
}

/** Public profile rules (ADR 0041, registries/profiles.json). The server checks the same rules and is the authority. */
object BipePublicProfileRules {
    /** `null` when the name and bio pass. Lengths count code points, like the API. */
    fun profileProblem(name: String, bio: String): BipeProfileProblem? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return BipeProfileProblem.NAME_EMPTY
        if (trimmed.codePointCount(0, trimmed.length) > BipeRegistries.PROFILE_NAME_MAX_LENGTH) return BipeProfileProblem.NAME_TOO_LONG
        if (NameNormalizer.reservedCategory(trimmed) != null) return BipeProfileProblem.NAME_RESERVED
        val trimmedBio = bio.trim()
        if (trimmedBio.codePointCount(0, trimmedBio.length) > BipeRegistries.PROFILE_BIO_MAX_LENGTH) return BipeProfileProblem.BIO_TOO_LONG
        if (trimmedBio.split("\n").size > BipeRegistries.PROFILE_BIO_MAX_LINES) return BipeProfileProblem.BIO_TOO_MANY_LINES
        return null
    }

    /** The name rules alone (the Matrix display name of profile-edit has no bio). */
    fun nameProblem(name: String): BipeProfileProblem? = profileProblem(name, "")
}

/** Phone numbers for discovery only, never identity (ADR 0015). Rule: registries/phone-numbers.json. */
object BipePhoneNumber {
    /** E.164 from an address-book entry, or `null`: Brazilian mobiles in any local format, other countries only with "+" or "00". */
    fun normalize(text: String): String? {
        val head = text.take(BipeRegistries.PHONE_MAX_INPUT_LENGTH).replace("(0)", "").trim()
        var plus = head.filter { it in '0'..'9' || it == '+' }.startsWith("+")
        var digits = head.filter { it in '0'..'9' }
        if (!plus && digits.startsWith("00")) {
            plus = true
            digits = digits.drop(2)
        }
        val countryCode = BipeRegistries.PHONE_COUNTRY_CODE
        val national = BipeRegistries.PHONE_MOBILE_LENGTH + 2
        if (plus) {
            if (!digits.startsWith(countryCode)) {
                // Another country: E.164 as written, length checked only.
                val ok = digits.length in BipeRegistries.PHONE_INTERNATIONAL_MIN_DIGITS..BipeRegistries.PHONE_INTERNATIONAL_MAX_DIGITS &&
                    !digits.startsWith("0")
                return if (ok) "+$digits" else null
            }
            digits = digits.drop(countryCode.length)
        } else if (digits.length == national + 3 && digits.startsWith("0")) {
            digits = digits.drop(3) // trunk 0 + carrier code
        } else if (digits.length == national + 1 && digits.startsWith("0")) {
            digits = digits.drop(1) // trunk 0
        } else if (digits.length == national + countryCode.length && digits.startsWith(countryCode)) {
            digits = digits.drop(countryCode.length)
        }
        if (digits.length != national) return null
        val area = digits.take(2)
        val subscriber = digits.drop(2)
        if (area !in BipeRegistries.phoneAreaCodes || !subscriber.startsWith(BipeRegistries.PHONE_MOBILE_PREFIX)) return null
        return "+$countryCode$area$subscriber"
    }
}

/** CNPJ (ADR 0042): numeric and alphanumeric CNPJs share one rule. */
object BipeCnpj {
    private val weights1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    private val weights2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    private val shape = Regex("^[0-9A-Z]{12}[0-9]{2}$")

    /** The CNPJ as 14 uppercase characters, or `null` when the format or a check digit is wrong. */
    fun normalize(input: String): String? {
        val value = input.filterNot { it in ".-/" || it.isWhitespace() }.uppercase()
        if (!shape.matches(value) || value.toSet().size == 1) return null
        val base = value.take(12)
        val first = checkDigit(base, weights1)
        val second = checkDigit(base + first, weights2)
        return if (value.endsWith("$first$second")) value else null
    }

    /** `12.ABC.345/01DE-35` from the 14 characters of a normalised CNPJ. */
    fun formatted(cnpj: String): String =
        if (cnpj.length != 14) cnpj else "${cnpj.substring(0, 2)}.${cnpj.substring(2, 5)}.${cnpj.substring(5, 8)}/${cnpj.substring(8, 12)}-${cnpj.substring(12)}"

    private fun checkDigit(chars: String, weights: IntArray): Int {
        val remainder = chars.indices.sumOf { (chars[it].code - 48) * weights[it] } % 11
        return if (remainder < 2) 0 else 11 - remainder
    }
}

/**
 * Profile links: `https://bipe.chat/@handle`. Links on the old host proseio.com.br still parse: that host redirects.
 * Same rule as BipeKit BipeProfileLink on iOS.
 */
object BipeProfileLink {
    const val HOST = "bipe.chat"
    private val acceptedHosts = setOf(HOST, "www.$HOST", "proseio.com.br", "www.proseio.com.br")

    fun url(handle: String): String = "https://$HOST/@$handle"

    /** The handle in a profile link, lowercased, or `null` when the URL is not one. */
    fun handle(url: android.net.Uri): String? {
        if (url.scheme != "https" || url.host?.lowercase() !in acceptedHosts) return null
        val segments = url.pathSegments ?: return null
        val first = segments.singleOrNull() ?: return null
        if (!first.startsWith("@")) return null
        val handle = BipeHandle.sanitize(first).lowercase()
        return if (BipeHandle.localProblem(handle) == null) handle else null
    }

    /** The Matrix user ID in a `https://matrix.to/#/@user:server` permalink. */
    fun matrixUserId(url: android.net.Uri): String? {
        if (url.host != "matrix.to") return null
        val fragment = url.fragment ?: return null
        val path = fragment.substringBefore('?')
        val userId = path.removePrefix("/")
        return if (userId.startsWith("@") && !userId.contains('/')) userId else null
    }
}
