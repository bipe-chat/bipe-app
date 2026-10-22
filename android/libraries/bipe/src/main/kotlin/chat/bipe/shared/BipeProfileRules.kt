// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import androidx.annotation.StringRes
import chat.bipe.shared.generated.BipeRegistries

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
