// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import androidx.annotation.StringRes
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Same ids as `handleProblem()` in bipe-shared/tools/lib/registries.mjs and the API's `HandleProblem`. */
enum class BipeHandleProblem(val id: String) {
    TOO_SHORT("too_short"),
    TOO_LONG("too_long"),
    INVALID_CHARACTERS("invalid_characters"),
    RESERVED("reserved"),

    /** Also covers held and lookalike handles (server only). */
    TAKEN("taken");

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            TOO_SHORT -> R.string.bipe_handle_too_short
            TOO_LONG -> R.string.bipe_handle_too_long
            INVALID_CHARACTERS -> R.string.bipe_handle_invalid_characters
            RESERVED -> R.string.bipe_handle_reserved
            TAKEN -> R.string.bipe_handle_taken
        }

    /** Format arguments for [messageRes]. */
    val messageArgs: Array<Any>
        get() = when (this) {
            TOO_SHORT -> arrayOf(BipeRegistries.HANDLE_MIN_LENGTH.toString())
            TOO_LONG -> arrayOf(BipeRegistries.HANDLE_MAX_LENGTH.toString())
            else -> emptyArray()
        }

    fun localizedMessage(stringProvider: BipeStrings): String = stringProvider.getString(messageRes, *messageArgs)

    companion object {
        fun fromId(id: String): BipeHandleProblem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Client-side handle checks for early feedback. The server is the authority:
 * reserved names and lookalikes are checked there through the availability endpoint.
 */
object BipeHandle {
    private val pattern = Regex(BipeRegistries.HANDLE_PATTERN)

    /** Trims whitespace and a leading "@" the user may type. */
    fun sanitize(input: String): String = input.trim().removePrefix("@")

    /**
     * Length and character checks only, the same as a lookup. `null` means the server must decide.
     * An owned handle may be shorter than HANDLE_MIN_LENGTH (ADR 0065); the server decides who may claim it.
     */
    fun localProblem(handle: String): BipeHandleProblem? {
        // JS `length` and Kotlin `length` both count UTF-16 units. The pattern is ASCII-only, so non-ASCII input fails it anyway.
        if (handle.length < BipeRegistries.HANDLE_OWNER_MIN_LENGTH) return BipeHandleProblem.TOO_SHORT
        if (handle.length > BipeRegistries.HANDLE_MAX_LENGTH) return BipeHandleProblem.TOO_LONG
        if (handle.length < BipeRegistries.HANDLE_MIN_LENGTH && handle !in BipeRegistries.ownedHandles) return BipeHandleProblem.TOO_SHORT
        if (!pattern.containsMatchIn(handle)) return BipeHandleProblem.INVALID_CHARACTERS
        return null
    }
}

/** Client-side checks for the sign-up form. Limits come from the API contract (`SignupRequest`). */
object BipeSignupRules {
    const val PASSWORD_MIN_LENGTH = 8
    const val PASSWORD_MAX_LENGTH = 256
    const val INVITE_CODE_MAX_LENGTH = 64
    const val EMAIL_MAX_LENGTH = 254
    const val DISPLAY_NAME_MAX_LENGTH = 100

    private val emailShape = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /** A loose shape check. MAS and the API validate the address for real. */
    fun isPlausibleEmail(email: String): Boolean =
        email.codePointCount(0, email.length) <= EMAIL_MAX_LENGTH && emailShape.containsMatchIn(email)

    fun isValidPassword(password: String): Boolean =
        password.codePointCount(0, password.length) in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH
}

/** Result of checking a handle the user typed. */
sealed interface BipeHandleStatus {
    data object Empty : BipeHandleStatus
    data object Checking : BipeHandleStatus
    data object Available : BipeHandleStatus
    data class Problem(val problem: BipeHandleProblem) : BipeHandleStatus

    /** The check failed (offline or server error). The server decides on submit. */
    data object Unknown : BipeHandleStatus

    val isBlocking: Boolean get() = this is Problem

    /** Footer text for the handle field. [defaultText] shows when there is nothing to report. */
    fun footer(defaultText: String, stringProvider: BipeStrings): String = when (this) {
        Empty, Unknown -> defaultText
        Checking -> stringProvider.getString(R.string.bipe_handle_checking)
        Available -> stringProvider.getString(R.string.bipe_handle_available)
        is Problem -> problem.localizedMessage(stringProvider)
    }
}

/** Debounced availability check: local rules at once, then the API after a pause in typing. */
class BipeHandleAvailabilityChecker(
    private val apiClient: BipeApiClient,
    private val scope: CoroutineScope,
    private val debounce: Duration = 500.milliseconds,
) {
    private var job: Job? = null

    /** Cancels any check in flight. [update] gets every status change for [input], in [scope]. */
    fun check(input: String, update: (BipeHandleStatus) -> Unit) {
        job?.cancel()

        val handle = BipeHandle.sanitize(input)
        if (handle.isEmpty()) {
            update(BipeHandleStatus.Empty)
            return
        }
        BipeHandle.localProblem(handle)?.let {
            update(BipeHandleStatus.Problem(it))
            return
        }

        update(BipeHandleStatus.Checking)
        job = scope.launch {
            delay(debounce)
            // Cancellation propagates out of handleAvailability, so a stale result never reaches update.
            val status = try {
                val availability = apiClient.handleAvailability(handle)
                if (availability.available) {
                    BipeHandleStatus.Available
                } else {
                    BipeHandleStatus.Problem(availability.problem ?: BipeHandleProblem.TAKEN)
                }
            } catch (_: BipeApiException) {
                BipeHandleStatus.Unknown
            }
            update(status)
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
