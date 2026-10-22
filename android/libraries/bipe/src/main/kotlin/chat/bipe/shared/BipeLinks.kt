// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import java.net.URI
import java.util.Locale

/**
 * Profile links: `https://bipe.chat/@handle`. The web page there falls back to the store;
 * the app opens it as an app link. Links on the old host `proseio.com.br` still parse: it redirects to `bipe.chat`.
 */
object BipeProfileLink {
    const val HOST = "bipe.chat"

    /** Old host, kept so shared old links still open. It redirects to [HOST]. */
    private const val LEGACY_HOST = "proseio.com.br"
    private val acceptedHosts = setOf(HOST, "www.$HOST", LEGACY_HOST, "www.$LEGACY_HOST")

    fun url(handle: String): String? =
        runCatchingExceptions { URI("https", HOST, "/@$handle", null).toASCIIString() }.getOrNull()

    /** The handle in a profile link, lowercased, or `null` when the URL is not one. */
    fun handle(url: String): String? {
        val components = bipePathComponents(url) ?: return null
        val first = components.singleOrNull()?.takeIf { it.startsWith("@") } ?: return null
        val handle = BipeHandle.sanitize(first).lowercase(Locale.ROOT)
        return handle.takeIf { BipeHandle.localProblem(it) == null }
    }

    /** The Matrix user ID in a `https://matrix.to/#/@user:server` permalink. */
    fun matrixUserId(permalink: String): String? {
        val uri = runCatchingExceptions { URI(permalink) }.getOrNull() ?: return null
        if (uri.host != "matrix.to") return null
        val fragment = uri.fragment ?: return null
        val userId = fragment.substringBefore('?').removePrefix("/")
        return userId.takeIf { it.startsWith("@") && !it.contains('/') }
    }

    /** Decoded, non-empty path components of an `https` URL on the Bipe host, or `null`. */
    internal fun bipePathComponents(url: String): List<String>? {
        val uri = runCatchingExceptions { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme != "https" || host !in acceptedHosts) return null
        return uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    }
}

/**
 * Invite links: `https://bipe.chat/convite/XXXX-XXXX-XXXX`. The web page there explains how to install;
 * the app opens it as an app link and pre-fills the sign-up code.
 */
object BipeInviteLink {
    /** Rule: bipe-shared/registries/invite-codes.json (generated). Cases: test-vectors/invite-codes.json. */
    private val pattern = Regex(BipeRegistries.INVITE_CODE_PATTERN)

    /** The invite code in a link, normalised, or `null` when the URL is not an invite link. */
    fun code(url: String): String? {
        val components = BipeProfileLink.bipePathComponents(url) ?: return null
        if (components.size != 2 || components[0] != "convite") return null
        return normalizedCode(components[1])
    }

    /** `XXXX-XXXX-XXXX` from anything a user typed or pasted: a code, an invite link or a whole message. `null` when it holds no code. */
    fun normalizedCode(raw: String): String? {
        val text = raw.take(BipeRegistries.INVITE_CODE_MAX_INPUT_LENGTH).uppercase(Locale.ROOT)
        val match = pattern.find(text) ?: return null
        return match.groupValues.drop(1).joinToString("-")
    }
}

/**
 * Recovery links from the Bipe API e-mail (ADR 0057): `https://bipe.chat/recuperar/#<token>`. The token is in the fragment,
 * so it stays out of server logs. The app opens the link as an app link on the new phone.
 */
object BipeRecoveryLink {
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{16,128}$")

    /** The token in a recovery link, or `null` when the URL is not one. */
    fun token(url: String): String? {
        val components = BipeProfileLink.bipePathComponents(url) ?: return null
        if (components != listOf("recuperar")) return null
        val fragment = runCatchingExceptions { URI(url).fragment }.getOrNull() ?: return null
        return fragment.takeIf { tokenPattern.matches(it) }
    }
}

/**
 * The Termos de uso and the Política de privacidade on the public site (ADR 0113). Paths: bipe-shared/registries/legal.json (generated).
 * English pages for an English device, else Portuguese: the same rule as the `lang` the API gets.
 */
object BipeLegalLinks {
    fun termsUrl(): String = url(if (isEnglish()) BipeRegistries.EN_TERMS_PATH else BipeRegistries.TERMS_PATH)

    fun privacyUrl(): String = url(if (isEnglish()) BipeRegistries.EN_PRIVACY_PATH else BipeRegistries.PRIVACY_PATH)

    private fun isEnglish() = BipeApiClient.emailLanguage() == "en"

    private fun url(path: String) = "https://${BipeProfileLink.HOST}$path"
}

/** A normalised invite code. Prints redacted, because routes and actions get logged. */
@JvmInline
value class BipeInviteCode private constructor(val value: String) {
    override fun toString(): String = "BipeInviteCode(redacted)"

    companion object {
        /** `null` when [raw] cannot be a code. */
        fun parse(raw: String): BipeInviteCode? = BipeInviteLink.normalizedCode(raw)?.let(::BipeInviteCode)
    }
}
