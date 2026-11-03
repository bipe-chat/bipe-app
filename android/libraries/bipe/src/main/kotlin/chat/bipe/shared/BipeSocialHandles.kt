// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.SocialPlatform

/**
 * Social handles on a public profile (ADR 0228, registries/profiles.json socials). Port of `socialHandleClean()`,
 * `socialHandleProblem()` and `socialUrl()` in bipe-shared/tools/lib/registries.mjs.
 * Must match bipe-shared/test-vectors/social-handles.json. The server is the authority.
 */
object BipeSocialHandles {
    private val patterns: Map<String, Regex> by lazy { BipeRegistries.profileSocials.associate { it.id to Regex(it.pattern) } }

    /** The registry entry of [id], or `null` for an id this build does not know. */
    fun platform(id: String): SocialPlatform? = BipeRegistries.profileSocials.firstOrNull { it.id == id }

    /** `socialHandleClean()`: JS trim(), then one leading "@". */
    fun clean(input: String): String = NameNormalizer.trimJavaScript(input).removePrefix("@")

    /**
     * `socialHandleProblem()`: `null` for an empty field or a valid handle. [ownHandle] is the user's own Bipe @:
     * a reserved skeleton passes when it is the skeleton of that @.
     */
    fun problem(platform: SocialPlatform, input: String, ownHandle: String?): BipeProfileProblem? {
        val handle = clean(input)
        if (handle.isEmpty()) return null
        val pattern = patterns[platform.id] ?: Regex(platform.pattern)
        if (!pattern.matches(handle)) return BipeProfileProblem.SOCIAL_INVALID
        val skeleton = BipeHandle.skeleton(handle)
        if (skeleton in BipeHandle.reservedSkeletons && ownHandle?.let(BipeHandle::skeleton) != skeleton) {
            return BipeProfileProblem.SOCIAL_RESERVED
        }
        return null
    }

    /** `socialUrl()`: the registry template with the cleaned handle. Built on the device; never a URL from the server. */
    fun url(platform: SocialPlatform, input: String): String = platform.url.replace("{handle}", clean(input))
}
