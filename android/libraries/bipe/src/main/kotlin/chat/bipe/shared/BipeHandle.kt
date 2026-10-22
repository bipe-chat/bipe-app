// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Same ids as `handleProblem()` in bipe-shared tools/lib/registries.mjs and the API's `HandleProblem`. */
enum class BipeHandleProblem(val id: String) {
    TOO_SHORT("too_short"),
    TOO_LONG("too_long"),
    INVALID_CHARACTERS("invalid_characters"),
    RESERVED("reserved"),

    /** Also held and lookalike handles (the server decides). */
    TAKEN("taken"),
    ;

    fun localizedMessage(context: Context): String = when (this) {
        TOO_SHORT -> context.getString(R.string.bipe_handle_too_short, BipeRegistries.HANDLE_MIN_LENGTH.toString())
        TOO_LONG -> context.getString(R.string.bipe_handle_too_long, BipeRegistries.HANDLE_MAX_LENGTH.toString())
        INVALID_CHARACTERS -> context.getString(R.string.bipe_handle_invalid_characters)
        RESERVED -> context.getString(R.string.bipe_handle_reserved)
        TAKEN -> context.getString(R.string.bipe_handle_taken)
    }

    companion object {
        fun fromId(id: String): BipeHandleProblem? = entries.firstOrNull { it.id == id }
    }
}

/** Early handle checks (registries/handles.json, test-vectors/handles.json). The server is the authority. */
object BipeHandle {
    private val pattern = Regex(BipeRegistries.HANDLE_PATTERN)

    /** Trims spaces and one leading @. */
    fun sanitize(input: String): String = input.trim().removePrefix("@")

    /** Length and characters only. `null`: the server decides. Owned handles may be shorter (ADR 0065). */
    fun localProblem(handle: String): BipeHandleProblem? {
        val length = handle.length
        return when {
            length < BipeRegistries.HANDLE_OWNER_MIN_LENGTH -> BipeHandleProblem.TOO_SHORT
            length > BipeRegistries.HANDLE_MAX_LENGTH -> BipeHandleProblem.TOO_LONG
            length < BipeRegistries.HANDLE_MIN_LENGTH && handle !in BipeRegistries.ownedHandles -> BipeHandleProblem.TOO_SHORT
            !pattern.matches(handle) -> BipeHandleProblem.INVALID_CHARACTERS
            else -> null
        }
    }
}

/** The result of checking a typed handle. */
sealed interface BipeHandleStatus {
    data object Empty : BipeHandleStatus
    data object Checking : BipeHandleStatus
    data object Available : BipeHandleStatus
    data class Problem(val problem: BipeHandleProblem) : BipeHandleStatus

    /** Offline or a server error: the server decides on save. */
    data object Unknown : BipeHandleStatus
}

/** Local rules at once, then the availability call after a pause in typing. */
class BipeHandleAvailabilityChecker(
    private val apiClient: BipeApiClient,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500,
) {
    private var job: Job? = null

    fun check(input: String, update: (BipeHandleStatus) -> Unit) {
        job?.cancel()
        val handle = BipeHandle.sanitize(input)
        if (handle.isEmpty()) return update(BipeHandleStatus.Empty)
        BipeHandle.localProblem(handle)?.let { return update(BipeHandleStatus.Problem(it)) }
        update(BipeHandleStatus.Checking)
        job = scope.launch {
            delay(debounceMillis)
            val status = runCatching { apiClient.handleAvailability(handle) }.fold(
                onSuccess = { if (it.available) BipeHandleStatus.Available else BipeHandleStatus.Problem(it.problem ?: BipeHandleProblem.TAKEN) },
                onFailure = { BipeHandleStatus.Unknown },
            )
            update(status)
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

/** Form limits of the API contract (`SignupRequest`). */
object BipeSignupRules {
    const val EMAIL_MAX_LENGTH = 254
    const val DISPLAY_NAME_MAX_LENGTH = 100

    private val email = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /** A loose shape check. The API checks the address for real. */
    fun isPlausibleEmail(value: String): Boolean = value.length <= EMAIL_MAX_LENGTH && email.matches(value)
}

/** Invite codes and links (registries/invite-codes.json, test-vectors/invite-codes.json). */
object BipeInviteLink {
    private val pattern = Regex(BipeRegistries.INVITE_CODE_PATTERN)
    // The old host proseio.com.br redirects to bipe.chat, so its links still count.
    private val hosts = setOf("bipe.chat", "www.bipe.chat", "proseio.com.br", "www.proseio.com.br")

    /** `XXXX-XXXX-XXXX` from a code, a link or a whole message. `null` when it holds no code. */
    fun normalizedCode(raw: String): String? {
        val text = raw.take(BipeRegistries.INVITE_CODE_MAX_INPUT_LENGTH).uppercase()
        val match = pattern.find(text) ?: return null
        return match.groupValues.drop(1).joinToString("-")
    }

    /** The code of a `https://bipe.chat/convite/<code>` link. */
    fun code(url: android.net.Uri): String? {
        if (url.scheme != "https" || url.host?.lowercase() !in hosts) return null
        val segments = url.pathSegments
        if (segments.size != 2 || segments[0] != "convite") return null
        return normalizedCode(segments[1])
    }
}

/** The Terms of use and the Privacy policy on the public site (ADR 0113). English devices get the English pages. */
object BipeLegalLinks {
    fun termsUrl(site: String): String = site.trimEnd('/') + if (isEnglish) BipeRegistries.EN_TERMS_PATH else BipeRegistries.TERMS_PATH

    fun privacyUrl(site: String): String = site.trimEnd('/') + if (isEnglish) BipeRegistries.EN_PRIVACY_PATH else BipeRegistries.PRIVACY_PATH

    private val isEnglish: Boolean get() = BipeApiClient.emailLanguage() == "en"
}

/** Recovery links in the e-mail: `https://bipe.chat/recuperar/#<token>`. The token sits in the fragment, out of server logs. */
object BipeRecoveryLink {
    private val hosts = setOf("bipe.chat", "www.bipe.chat", "proseio.com.br", "www.proseio.com.br")
    private val allowed = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** The link token, or `null` when the link is not a recovery link. */
    fun token(url: android.net.Uri): String? {
        if (url.scheme != "https" || url.host?.lowercase() !in hosts) return null
        if (url.pathSegments != listOf("recuperar")) return null
        val token = url.fragment?.trim() ?: return null
        return token.takeIf { allowed.matches(it) }
    }
}
