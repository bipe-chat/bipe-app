// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Social handles on a public profile (ADR 0228, registries/profiles.json socials). Port of `socialHandleClean()`,
/// `socialHandleProblem()` and `socialUrl()` in bipe-shared/tools/lib/registries.mjs.
/// Must match bipe-shared/test-vectors/social-handles.json. The server is the authority.
public nonisolated enum BipeSocialHandles {
    /// The registry entry of `id`, or nil for an id this build does not know.
    public static func platform(_ id: String) -> SocialPlatform? {
        BipeRegistries.profileSocials.first { $0.id == id }
    }

    /// `socialHandleClean()` in bipe-core: JS trim(), then one leading "@".
    public static func clean(_ input: String) -> String {
        socialHandleClean(input: input)
    }

    /// `socialHandleProblem()` in bipe-core: nil for an empty field or a valid handle. `ownHandle` is the user's own
    /// Bipe @: a reserved skeleton passes when it is the skeleton of that @.
    public static func problem(_ platform: SocialPlatform, input: String, ownHandle: String?) -> BipeProfileProblem? {
        socialHandleProblem(platform: platform.id, input: input, ownHandle: ownHandle).map { problem in
            switch problem {
            case .invalid: .socialInvalid
            case .reserved: .socialReserved
            }
        }
    }

    /// `socialUrl()` in bipe-core: the registry template with the cleaned handle. Built on the device; never a URL from
    /// the server.
    public static func url(_ platform: SocialPlatform, input: String) -> String {
        socialUrl(platform: platform.id, input: input) ?? platform.url.replacingOccurrences(of: "{handle}", with: clean(input))
    }
}
