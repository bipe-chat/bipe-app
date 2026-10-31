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

    /// Spec chat-list-prompt (bipe-chat/bipe#894): the one card of the chat list, from the entries whose condition holds.
    public func chatListCard(eligible: Set<BipeChatListPrompt>, now: Date = Date()) -> BipeChatListPrompt? {
        Self.chatListCard(eligible: eligible, holder: defaults.string(forKey: holderKey),
                          since: defaults.object(forKey: sinceKey) as? Date, now: now)
    }

    /// Call when `card` shows. A preempting card never takes the slot.
    public func didShowCard(_ card: BipeChatListPrompt, now: Date = Date()) {
        guard !card.preempts, defaults.string(forKey: holderKey) != card.rawValue,
              Self.chatListCard(eligible: [card], holder: defaults.string(forKey: holderKey),
                                since: defaults.object(forKey: sinceKey) as? Date, now: now) == card else { return }
        defaults.set(card.rawValue, forKey: holderKey)
        defaults.set(now, forKey: sinceKey)
    }

    /// The pick rule. Reference: chatListPrompt() in bipe-shared tools/lib/registries.mjs; vectors in
    /// onboarding-schedule.json chatListPrompt. A preempting entry wins at once; else the first entry in queue order
    /// that the slot allows (no holder, the holder itself, or promptSlotHours since the holder took the slot).
    public static func chatListCard(eligible: Set<BipeChatListPrompt>, holder: String?, since: Date?, now: Date) -> BipeChatListPrompt? {
        let entries = BipeChatListPrompt.allCases.filter(eligible.contains)
        if let first = entries.first(where: \.preempts) { return first }
        return entries.first { entry in
            guard let holder, let since else { return true }
            return holder == entry.rawValue || now.timeIntervalSince(since) >= Double(BipeOnboarding.promptSlotHours) * 3600
        }
    }
}
