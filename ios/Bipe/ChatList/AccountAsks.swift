// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec chat-list-prompt entries notifications, osTheftProtection and photo (bipe-chat/bipe#894, MZ 2 Nov 2026):
/// the asks that had a place of their own before (the notifications sheet, the Você cards). Each keeps the
/// condition of its source spec; chat-list-prompt picks which one shows.
@MainActor
final class AccountAsksModel: ObservableObject {
    @Published private(set) var showsNotifications = false
    @Published private(set) var showsOsTheft = false
    @Published private(set) var showsPhoto = false

    private let session: AccountSession
    private var photoKey: String { "bipe.photoPrompt.dismissed.\(session.userId)" }

    init(session: AccountSession) {
        self.session = session
    }

    func load() async {
        await PushRegistration.shared.appBecameActive()
        showsNotifications = !NotificationsPromptMemory.isDone
            && NotificationsPromptMemory.messageSent(userID: session.userId)
            && PushRegistration.shared.permission == .notAsked
        showsOsTheft = !OsTheftPromptMemory.isDone && OsTheftPromptMemory.isDue(userID: session.userId)
        guard !UserDefaults.standard.bool(forKey: photoKey) else { showsPhoto = false; return }
        // Only a profile that loaded without a photo shows the card; a failed read shows nothing.
        do { showsPhoto = try await session.client.avatarUrl() == nil } catch { showsPhoto = false }
    }

    /// Ligar notificações: the system dialog; allowed registers this phone. Either way the card ends for good.
    func allowNotifications() async {
        _ = await PushRegistration.shared.requestPermission()
        NotificationsPromptMemory.markDone()
        showsNotifications = false
    }

    func dismissNotifications() {
        NotificationsPromptMemory.markDone()
        showsNotifications = false
    }

    /// Conferir proteção or Agora não: the card never comes back on this phone, for every account.
    func finishOsTheft() {
        OsTheftPromptMemory.markDone()
        showsOsTheft = false
    }

    /// Agora não: the photo card never comes back on this account.
    func dismissPhoto() {
        UserDefaults.standard.set(true, forKey: photoKey)
        showsPhoto = false
    }
}

/// One card of AccountAsksModel in the shape of every chat list prompt.
struct AccountAskCard: View {
    let prompt: BipeOnboardingPrompt
    var title: String?
    var note: String?
    let open: () -> Void
    let dismiss: () -> Void

    var body: some View {
        PromptCard(title: title ?? BipeLocalized.string(prompt.titleKey),
                   body: prompt.bodyKey.map { BipeLocalized.string($0) },
                   note: note,
                   primary: .init(title: prompt.primary.title, run: open),
                   texts: prompt.text.map { .init(title: $0.title, run: dismiss) })
    }
}
