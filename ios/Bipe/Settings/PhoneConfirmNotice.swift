// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Spec phone-link-status, action checkNotice (bipe-chat/bipe#470): one in-app notice when the yearly confirmation of
/// the linked number is near or has run out. One notice per confirm_by, kept on this phone.
@MainActor
final class PhoneConfirmNoticeModel: ObservableObject {
    @Published private(set) var notice: BipePhoneLinkState?

    private let api: BipeAPIClient
    private let session: AccountSession
    private let defaults = UserDefaults.standard

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
    }

    private var shownKey: String { "bipe.phoneConfirm.shown.\(session.userId)" }

    /// On launch and on foreground.
    func check() async {
        guard notice == nil else { return }
        let api = api
        guard let phone = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, { (token: String) async throws(BipeAPIError) -> BipeMyPhone in
            try await api.myPhone(accessToken: token)
        }) else { return }
        let state = BipePhoneLinkState(phone: phone)
        guard state.needsAttention, let confirmBy = state.confirmBy else { return }
        // A new notice for the soon state and again once it has run out, never twice for one of them.
        let key = "\(Int(confirmBy.timeIntervalSince1970)):\(state.isExpired ? "expired" : "soon")"
        guard defaults.string(forKey: shownKey) != key else { return }
        defaults.set(key, forKey: shownKey)
        notice = state
    }

    func dismiss() {
        notice = nil
    }
}

private extension BipePhoneLinkState {
    var isExpired: Bool {
        if case .expired = self { return true }
        return false
    }
}
