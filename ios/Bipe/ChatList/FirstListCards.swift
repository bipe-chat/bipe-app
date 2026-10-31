// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec chat-list-cards (bipe-chat/bipe#198): which cards of registries/onboarding.json firstList still show on this
/// account. A used or dismissed card never comes back.
@MainActor
final class FirstListCardsModel: ObservableObject {
    @Published private(set) var done: Set<BipeOnboardingCard>
    private let key: String

    init(userId: String) {
        key = "bipe.firstList.done.\(userId)"
        let saved = UserDefaults.standard.stringArray(forKey: key) ?? []
        done = Set(saved.compactMap(BipeOnboardingCard.init(rawValue:)))
    }

    /// State cards: registry order, only while Conversas lists at most firstListMaxChats chats.
    func visible(chatCount: Int, handle: String?) -> [BipeOnboardingCard] {
        guard chatCount <= BipeOnboarding.firstListMaxChats else { return [] }
        return BipeOnboardingCard.allCases.filter { !done.contains($0) && (!$0.needsHandle || handle != nil) }
    }

    /// Actions dismiss, and every other action once it ran: the card goes for good.
    func finish(_ card: BipeOnboardingCard) {
        done.insert(card)
        UserDefaults.standard.set(done.map(\.rawValue).sorted(), forKey: key)
    }
}

struct FirstListCardView: View {
    let card: BipeOnboardingCard
    let handle: String?
    let action: (BipeOnboardingTarget) -> Void

    var body: some View {
        PromptCard(title: BipeLocalized.string(card.titleKey), body: bodyText,
                   primary: .init(title: card.primary.title) { action(card.primary.target) },
                   texts: card.text.map { button in .init(title: button.title) { action(button.target) } })
    }

    private var bodyText: String {
        if card == .profile, let handle { return BipeStrings.firstCardProfileBody(handle) }
        return card.bodyKey.map { BipeLocalized.string($0) } ?? ""
    }
}
