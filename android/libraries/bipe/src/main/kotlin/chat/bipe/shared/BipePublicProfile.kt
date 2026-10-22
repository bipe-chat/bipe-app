// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import androidx.annotation.StringRes
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeMyProfile
import chat.bipe.shared.api.BipePublicProfile
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/** Same ids as `profileProblem()` in bipe-shared/tools/lib/registries.mjs and the API's `ProfileProblem`. */
enum class BipeProfileProblem(val id: String) {
    NAME_EMPTY("name_empty"),
    NAME_TOO_LONG("name_too_long"),
    NAME_RESERVED("name_reserved"),
    BIO_TOO_LONG("bio_too_long"),
    BIO_TOO_MANY_LINES("bio_too_many_lines"),

    /** Server only: a profile cannot be turned on without a handle. */
    NO_HANDLE("no_handle");

    val isNameProblem: Boolean get() = this == NAME_EMPTY || this == NAME_TOO_LONG || this == NAME_RESERVED
    val isBioProblem: Boolean get() = this == BIO_TOO_LONG || this == BIO_TOO_MANY_LINES

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            NAME_EMPTY -> R.string.bipe_public_profile_name_empty
            NAME_TOO_LONG -> R.string.bipe_public_profile_name_too_long
            NAME_RESERVED -> R.string.bipe_warning_reserved_name
            BIO_TOO_LONG -> R.string.bipe_public_profile_bio_too_long
            BIO_TOO_MANY_LINES -> R.string.bipe_public_profile_bio_too_many_lines
            NO_HANDLE -> R.string.bipe_public_profile_needs_handle
        }

    /** Format arguments for [messageRes]: the limit from registries/profiles.json. */
    val messageArgs: Array<Any>
        get() = when (this) {
            NAME_TOO_LONG -> arrayOf(BipeRegistries.PROFILE_NAME_MAX_LENGTH.toString())
            BIO_TOO_LONG -> arrayOf(BipeRegistries.PROFILE_BIO_MAX_LENGTH.toString())
            BIO_TOO_MANY_LINES -> arrayOf(BipeRegistries.PROFILE_BIO_MAX_LINES.toString())
            else -> emptyArray()
        }

    fun localizedMessage(stringProvider: BipeStrings): String = stringProvider.getString(messageRes, *messageArgs)

    companion object {
        fun fromId(id: String): BipeProfileProblem? = entries.firstOrNull { it.id == id }
    }
}

/** Client-side profile checks for early feedback (ADR 0041). The server is the authority. */
object BipeProfileRules {
    private val lineBreaks = Regex("\r\n?")

    /** Port of `profileProblem()` in bipe-shared/tools/lib/registries.mjs. Lengths count code points. */
    fun problem(name: String, bio: String): BipeProfileProblem? {
        val trimmedName = NameNormalizer.trimJavaScript(name)
        if (trimmedName.isEmpty()) return BipeProfileProblem.NAME_EMPTY
        if (trimmedName.codePointCount(0, trimmedName.length) > BipeRegistries.PROFILE_NAME_MAX_LENGTH) return BipeProfileProblem.NAME_TOO_LONG
        if (NameNormalizer.reservedCategory(trimmedName) != null) return BipeProfileProblem.NAME_RESERVED
        val trimmedBio = NameNormalizer.trimJavaScript(bio)
        if (trimmedBio.codePointCount(0, trimmedBio.length) > BipeRegistries.PROFILE_BIO_MAX_LENGTH) return BipeProfileProblem.BIO_TOO_LONG
        if (trimmedBio.split("\n").size > BipeRegistries.PROFILE_BIO_MAX_LINES) return BipeProfileProblem.BIO_TOO_MANY_LINES
        return null
    }

    /**
     * The problem the server reports on `PUT /v1/me/profile`: it normalises line breaks first, and it accepts
     * an empty draft while the profile is off. A missing handle is checked by the caller.
     */
    fun saveProblem(enabled: Boolean, name: String, bio: String): BipeProfileProblem? {
        val normalizedBio = bio.replace(lineBreaks, "\n")
        val isEmptyDraft = NameNormalizer.trimJavaScript(name).isEmpty() && NameNormalizer.trimJavaScript(normalizedBio).isEmpty()
        if (!enabled && isEmptyDraft) return null
        val problem = problem(name, normalizedBio) ?: return null
        return problem.takeIf { enabled || it != BipeProfileProblem.NAME_EMPTY }
    }
}

/**
 * Public profiles for one user session: other people's by handle, and the user's own.
 *
 * Reads are synchronous like [BipeHandleResolver]: [profile] returns `null` at first, fetches
 * `GET /v1/profiles/{handle}`, and [profiles] emits when the answer arrives.
 */
class BipePublicProfiles(
    private val apiClient: BipeApiClient,
    private val scope: CoroutineScope,
    private val accessToken: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val profile: BipePublicProfile?, val expiryMillis: Long)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Lowercase handle to published profile, `null` when the handle has none. Emits on every cache change. */
    val profiles: StateFlow<Map<String, BipePublicProfile?>> get() = profilesFlow.asStateFlow()
    private val profilesFlow = MutableStateFlow<Map<String, BipePublicProfile?>>(emptyMap())

    /** The user's own profile, `null` until loaded. */
    val myProfile: StateFlow<BipeMyProfile?> get() = myProfileFlow.asStateFlow()
    private val myProfileFlow = MutableStateFlow<BipeMyProfile?>(null)

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    /** The cached published profile for [handle] (without "@"). Fetches it when the cache has no fresh value. */
    fun profile(handle: String): BipePublicProfile? {
        val key = handle.lowercase(Locale.ROOT)
        val entry = entries.value[key]
        val expiryMillis = entry?.expiryMillis ?: Long.MIN_VALUE
        if (expiryMillis < clock()) fetch(key)
        return entry?.profile
    }

    /** Loads the user's own profile into [myProfile]. Failures keep the old value. */
    suspend fun refreshMine() {
        try {
            storeMine(BipeApiClient.withTokenRetry(accessToken) { token -> apiClient.myProfile(token) })
        } catch (error: BipeApiException) {
            Timber.w(error, "Bipe profile load failed")
        }
    }

    /** Stores the user's own profile after a load or a save, and drops the cached public copy so it shows the change. */
    fun storeMine(profile: BipeMyProfile) {
        myProfileFlow.value = profile
        profile.handle?.lowercase(Locale.ROOT)?.let { key ->
            entries.update { it - key }
            profilesFlow.update { it - key }
        }
    }

    private fun fetch(key: String) {
        synchronized(lock) {
            if (!inFlight.add(key)) return
        }
        scope.launch {
            try {
                set(key, Entry(apiClient.publicProfile(key), clock() + FOUND_TTL_MILLIS))
            } catch (error: BipeApiException.Server) {
                if (error.statusCode == 404) {
                    set(key, Entry(null, clock() + MISSING_TTL_MILLIS))
                } else {
                    keepOldValue(key)
                }
            } catch (error: BipeApiException) {
                keepOldValue(key)
            } finally {
                synchronized(lock) { inFlight.remove(key) }
            }
        }
    }

    /** Keeps the old value, with a short expiry so views do not retry on every render while offline. */
    private fun keepOldValue(key: String) {
        set(key, Entry(entries.value[key]?.profile, clock() + MISSING_TTL_MILLIS))
    }

    private fun set(key: String, entry: Entry) {
        entries.update { it + (key to entry) }
        profilesFlow.update { it + (key to entry.profile) }
    }

    private companion object {
        val FOUND_TTL_MILLIS = 10.minutes.inWholeMilliseconds
        val MISSING_TTL_MILLIS = 2.minutes.inWholeMilliseconds
    }
}
