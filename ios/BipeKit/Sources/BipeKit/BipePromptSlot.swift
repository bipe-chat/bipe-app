// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The one prompt slot of an account (registries/onboarding.json promptSlot, bipe-chat/bipe#200): after a slot prompt
/// shows, no other slot prompt shows for `BipeOnboarding.promptSlotHours`. The prompt that holds the slot keeps showing.
/// The Android counterpart is `BipePromptSlot` in libraries/bipe.
public nonisolated struct BipePromptSlot {
    private let defaults: UserDefaults
    private let holderKey: String
    private let sinceKey: String

    public init(userID: String, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        holderKey = "bipe.promptSlot.holder.\(userID)"
        sinceKey = "bipe.promptSlot.since.\(userID)"
    }

    /// true when `prompt` may show now. A prompt outside the slot always may.
    public func allows(_ prompt: BipeOnboardingPrompt, now: Date = Date()) -> Bool {
        guard prompt.usesSlot, let holder = defaults.string(forKey: holderKey),
              let since = defaults.object(forKey: sinceKey) as? Date else { return true }
        return holder == prompt.rawValue || now.timeIntervalSince(since) >= Double(BipeOnboarding.promptSlotHours) * 3600
    }

    /// Call when `prompt` shows. It takes the slot unless it already holds it.
    public func didShow(_ prompt: BipeOnboardingPrompt, now: Date = Date()) {
        guard prompt.usesSlot, allows(prompt, now: now), defaults.string(forKey: holderKey) != prompt.rawValue else { return }
        defaults.set(prompt.rawValue, forKey: holderKey)
        defaults.set(now, forKey: sinceKey)
    }
}
