// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.weblink

import android.net.Uri

/**
 * The phone approves a web sign-in by QR code (MSC4108) in a private web view (ADR 0116, ADR 0118).
 *
 * The app signs in with `m.login.password`, so it has no MAS browser session. The web view fills the MAS login form
 * once with the hidden password, then the user approves on the MAS consent page. Same rules as iOS `BipeWebLink`.
 */
object BipeWebLink {
    /** The `/versions` unstable feature that a server with QR sign-in advertises. */
    const val QR_LOGIN_FEATURE = "org.matrix.msc4108"

    /** The MAS account page action that ends the session of one device (MSC4191). */
    const val SESSION_END_ACTION = "org.matrix.session_end"

    /** The localpart of a Matrix ID. The MAS login form accepts it as the username (ADR 0118 F5). */
    fun localpart(userId: String): String? {
        if (!userId.startsWith("@")) return null
        val colon = userId.indexOf(':')
        if (colon <= 1) return null
        return userId.substring(1, colon)
    }

    /** `true` only for an `https` URL on [masHost]. The web view loads nothing else. */
    fun isAllowed(url: Uri, masHost: String): Boolean =
        url.scheme.equals("https", ignoreCase = true) && url.host.equals(masHost, ignoreCase = true)

    /** The MAS consent page of a device code grant is `/device/<id>`. */
    fun isDeviceConsentPath(url: Uri): Boolean {
        val segments = url.pathSegments
        return segments.size >= 2 && segments[segments.size - 2] == "device"
    }

    /** The MAS account page that asks to end the session of [deviceId]. */
    fun sessionEndUrl(accountManagementUrl: String, deviceId: String): Uri =
        Uri.parse(accountManagementUrl).buildUpon()
            .appendQueryParameter("action", SESSION_END_ACTION)
            .appendQueryParameter("device_id", deviceId)
            .build()

    /** The first IPv4 or IPv6 address in the text of a page. The MAS consent page shows the IP of the browser (ADR 0118 F6). */
    fun firstIpAddress(text: String): String? =
        text.split(SEPARATORS)
            .map { it.trim('.', ':') }
            .firstOrNull { it.isNotEmpty() && (isIpv4(it) || isIpv6(it)) }

    private val SEPARATORS = Regex("""[\s()\[\],;"'<>]+""")
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    private val IPV6_CHARS = Regex("""^[0-9A-Fa-f:.]+$""")

    private fun isIpv4(value: String): Boolean = IPV4.matches(value)

    /** Two colons at least, so a time like 12:30 does not count. An IPv4 tail is allowed. */
    private fun isIpv6(value: String): Boolean {
        if (!IPV6_CHARS.matches(value) || value.count { it == ':' } < 2) return false
        val compressions = value.windowed(2).count { it == "::" }
        if (compressions > 1 || value.contains(":::")) return false
        val groups = value.split(':')
        val last = groups.last()
        val ipv4Tail = last.contains('.')
        if (ipv4Tail && !isIpv4(last)) return false
        val hexGroups = if (ipv4Tail) groups.dropLast(1) else groups
        if (hexGroups.any { it.length > 4 || it.contains('.') }) return false
        val count = hexGroups.count { it.isNotEmpty() } + if (ipv4Tail) 2 else 0
        return if (compressions == 1) count < 8 else count == 8
    }
}
