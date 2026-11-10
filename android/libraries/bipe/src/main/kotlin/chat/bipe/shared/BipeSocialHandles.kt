// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.SocialHandleProblem
import chat.bipe.core.socialHandleClean
import chat.bipe.core.socialHandleProblem
import chat.bipe.core.socialUrl
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.SocialPlatform

/**
 * Social handles on a public profile (ADR 0228, registries/profiles.json socials). Port of `socialHandleClean()`,
 * `socialHandleProblem()` and `socialUrl()` in bipe-shared/tools/lib/registries.mjs.
 * Must match bipe-shared/test-vectors/social-handles.json. The server is the authority.
 */
object BipeSocialHandles {
    /** The registry entry of [id], or `null` for an id this build does not know. */
    fun platform(id: String): SocialPlatform? = BipeRegistries.profileSocials.firstOrNull { it.id == id }

    /** `socialHandleClean()` in bipe-core: JS trim(), then one leading "@". */
    fun clean(input: String): String = socialHandleClean(input)

    /**
     * `socialHandleProblem()` in bipe-core: `null` for an empty field or a valid handle. [ownHandle] is the user's own
     * Bipe @: a reserved skeleton passes when it is the skeleton of that @.
     */
    fun problem(platform: SocialPlatform, input: String, ownHandle: String?): BipeProfileProblem? =
        when (socialHandleProblem(platform.id, input, ownHandle)) {
            SocialHandleProblem.INVALID -> BipeProfileProblem.SOCIAL_INVALID
            SocialHandleProblem.RESERVED -> BipeProfileProblem.SOCIAL_RESERVED
            null -> null
        }

    /** `socialUrl()` in bipe-core: the registry template with the cleaned handle. Built on the device; never a URL from the server. */
    fun url(platform: SocialPlatform, input: String): String =
        socialUrl(platform.id, input) ?: platform.url.replace("{handle}", clean(input))
}
