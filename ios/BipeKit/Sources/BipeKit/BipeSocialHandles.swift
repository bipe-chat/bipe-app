// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Social handles on a public profile (ADR 0228, registries/profiles.json socials). Port of `socialHandleClean()`,
/// `socialHandleProblem()` and `socialUrl()` in bipe-shared/tools/lib/registries.mjs.
/// Must match bipe-shared/test-vectors/social-handles.json. The server is the authority.
public nonisolated enum BipeSocialHandles {
    /// The registry entry of `id`, or nil for an id this build does not know.
    public static func platform(_ id: String) -> SocialPlatform? {
        BipeRegistries.profileSocials.first { $0.id == id }
    }

    /// `socialHandleClean()`: JS trim(), then one leading "@".
    public static func clean(_ input: String) -> String {
        let trimmed = BipePublicProfileRules.trim(input)
        // Per scalar like JS: an "@" followed by a combining mark is one Character in Swift.
        guard trimmed.unicodeScalars.first == "@" else { return trimmed }
        return String(trimmed.unicodeScalars.dropFirst())
    }

    /// `socialHandleProblem()`: nil for an empty field or a valid handle. `ownHandle` is the user's own Bipe @:
    /// a reserved skeleton passes when it is the skeleton of that @.
    public static func problem(_ platform: SocialPlatform, input: String, ownHandle: String?) -> BipeProfileProblem? {
        let handle = clean(input)
        if handle.isEmpty {
            return nil
        }
        // The whole string must match: ICU `$` also matches before a final line terminator, JS `$` does not.
        guard let range = handle.range(of: platform.pattern, options: .regularExpression),
              range == handle.startIndex..<handle.endIndex else {
            return .socialInvalid
        }
        let skeleton = BipeHandle.skeleton(handle)
        if BipeHandle.reservedSkeletons.contains(skeleton), ownHandle.map(BipeHandle.skeleton) != skeleton {
            return .socialReserved
        }
        return nil
    }

    /// `socialUrl()`: the registry template with the cleaned handle. Built on the device; never a URL from the server.
    public static func url(_ platform: SocialPlatform, input: String) -> String {
        platform.url.replacingOccurrences(of: "{handle}", with: clean(input))
    }
}
