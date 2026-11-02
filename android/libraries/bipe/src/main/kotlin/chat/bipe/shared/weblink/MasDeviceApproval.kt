// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The MAS device-grant approval of specs link-computer-approve and link-phone-approve without a web view
// (ADR 0224). Written from the specs and the MAS form fields they name: login form csrf, username, password;
// consent form csrf, confirm_device, action=consent; reject form csrf, action=reject.

package chat.bipe.shared.weblink

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * One approval: https pages on the MAS host only, redirects checked one by one, cookies in memory for this object
 * only, no cache. Drop it when the flow ends.
 */
class MasDeviceApproval(private val host: String) {
    /** The consent page, as the native consent screen shows it. */
    data class Consent(
        val pageUrl: String,
        /** The device card text of MAS (model, OS, browser), or null. */
        val device: String?,
        val ip: String?,
        internal val csrf: String,
    )

    /** Spec error codes. The log names the code, the status and the page path; never the query or a secret. */
    sealed class Failure(val code: String) : Exception(code) {
        object OffHost : Failure("consent-host")
        class Status(val status: Int) : Failure("consent-status-$status")
        object SecondLogin : Failure("consent-login")
        class NotConsentPage(val path: String) : Failure("consent-page")
        object ConsentNotGranted : Failure("consent-post")
        object Network : Failure("consent-network")
    }

    private val cookies = mutableMapOf<String, String>()

    /** Action open: the verification page, the login form once, then the consent page. */
    suspend fun open(verificationUrl: String, username: String, password: String): Consent {
        var page = load(verificationUrl, null)
        val login = page.forms().firstOrNull { it.selectFirst("input[type=password]") != null }
        if (login != null) {
            val fields = hiddenFields(login) + mapOf("username" to username, "password" to password)
            page = load(target(login, page), fields)
            if (page.forms().any { it.selectFirst("input[type=password]") != null }) throw Failure.SecondLogin
        }
        val url = page.location()
        val form = page.forms().firstOrNull { it.selectFirst("button[name=action][value=consent]") != null }
        val csrf = form?.let { hiddenFields(it)["csrf"] }
        if (!BipeLinkComputer.isDeviceConsentPath(url) || form == null || csrf == null) {
            throw Failure.NotConsentPage(runCatching { URL(url).path }.getOrDefault(""))
        }
        val device = page.select(".card-header .content > div").map { it.text().trim() }.filter { it.isNotEmpty() }
        val metadata = page.selectFirst(".session-card .metadata")?.text()
        return Consent(
            pageUrl = target(form, page),
            device = device.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            ip = metadata?.let(BipeLinkComputer::firstIpAddress),
            csrf = csrf,
        )
    }

    /** Action approve: posts the consent form. MAS answers with its granted page, which has no consent form. */
    suspend fun approve(consent: Consent) {
        val page = load(consent.pageUrl, mapOf("csrf" to consent.csrf, "confirm_device" to "on", "action" to "consent"))
        val stillAsks = page.forms().any {
            it.selectFirst("button[name=action][value=consent]") != null || it.selectFirst("input[type=password]") != null
        }
        if (stillAsks) throw Failure.ConsentNotGranted
    }

    /** Action reject: posts the reject form. A failure changes nothing for the user. */
    suspend fun reject(consent: Consent) {
        runCatching { load(consent.pageUrl, mapOf("csrf" to consent.csrf, "action" to "reject")) }
    }

    fun close() {
        cookies.clear()
    }

    private fun Document.forms(): List<Element> = select("form")

    private fun hiddenFields(form: Element): Map<String, String> =
        form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }

    /** A form without an action attribute posts to its own page. */
    private fun target(form: Element, page: Document): String =
        form.attr("abs:action").takeIf { form.hasAttr("action") && it.isNotEmpty() } ?: page.location()

    /** GET when fields is null, else a form POST. Follows redirects on the MAS host only. */
    private suspend fun load(start: String, fields: Map<String, String>?): Document = withContext(Dispatchers.IO) {
        var url = start
        var body = fields
        repeat(MAX_REDIRECTS) {
            if (!BipeWebLink.isAllowed(url, host)) throw Failure.OffHost
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "text/html")
                cookieHeader()?.let { setRequestProperty("Cookie", it) }
            }
            try {
                if (body != null) {
                    val encoded = body.entries.joinToString("&") { (key, value) ->
                        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }.encodeToByteArray()
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(encoded) }
                }
                val status = connection.responseCode
                keepCookies(connection)
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw Failure.Status(status)
                    url = URL(URL(url), location).toString()
                    // 303 and the usual 301/302 handling: the next request is a GET.
                    body = null
                    return@repeat
                }
                if (status >= 400) throw Failure.Status(status)
                val html = connection.inputStream.use { it.readBytes().decodeToString() }
                return@withContext Jsoup.parse(html, url)
            } catch (error: IOException) {
                throw Failure.Network
            } finally {
                connection.disconnect()
            }
        }
        throw Failure.Status(310)
    }

    private fun keepCookies(connection: HttpURLConnection) {
        val headers = connection.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        headers.orEmpty().forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) return@forEach
            val value = pair.substringAfter('=', "").trim()
            val expired = header.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) } || value.isEmpty()
            if (expired) cookies.remove(name) else cookies[name] = value
        }
    }

    private fun cookieHeader(): String? =
        cookies.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { (name, value) -> "$name=$value" }

    private companion object {
        const val MAX_REDIRECTS = 10
        const val TIMEOUT_MS = 20_000
    }
}
