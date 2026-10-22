// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

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

/** A normalised invite code. Prints redacted, because routes and actions get logged. */
@JvmInline
value class BipeInviteCode private constructor(val value: String) {
    override fun toString(): String = "BipeInviteCode(redacted)"

    companion object {
        /** `null` when [raw] cannot be a code. */
        fun parse(raw: String): BipeInviteCode? = BipeInviteLink.normalizedCode(raw)?.let(::BipeInviteCode)
    }
}
