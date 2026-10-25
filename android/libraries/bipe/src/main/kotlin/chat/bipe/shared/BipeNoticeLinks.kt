// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

/**
 * Links in an official notice (ADR 0036, bipe-chat/bipe#245). Must match bipe-shared tools/lib/notice.mjs and
 * test-vectors/notice-links.json. The rule is lexical, not a URL parser, so every platform agrees. Fail closed.
 */
object BipeNoticeLinks {
    private val DOMAIN = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+")

    /** A verified domain in an `institution` badge: lower case, at least one dot. */
    fun isDomain(value: String): Boolean = DOMAIN.matches(value)

    /** True when [url] may be a clickable link in a notice from an institution with the verified [domains]. */
    fun isClickable(url: String, domains: List<String>): Boolean {
        if (url.take(8).lowercase() != "https://") return false
        val authority = url.drop(8).takeWhile { it != '/' && it != '?' && it != '#' }
        // No user info (@), no port (:), no IP literal ([), no other characters.
        if (authority.isEmpty() || !authority.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }) {
            return false
        }
        val host = authority.lowercase().removeSuffix(".")
        if (!isDomain(host)) return false
        return domains.any { isDomain(it) && (host == it || host.endsWith(".$it")) }
    }
}
