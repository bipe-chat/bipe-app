// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The plus menu and member picker share the account's single invite model and sender.
struct InvitesEntryView: View {
    let session: AccountSession
    let openChat: (String) -> Void
    @EnvironmentObject private var tabs: AppTabsModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var people: PeopleService

    init(session: AccountSession, api: BipeAPIClient, media: MediaLoader, openChat: @escaping (String) -> Void) {
        self.session = session
        self.openChat = openChat
        _people = StateObject(wrappedValue: PeopleService(session: session, api: api, media: media))
    }

    var body: some View {
        NavigationStack {
            InvitesView(model: tabs.invites(for: session), people: people) { roomId in
                dismiss()
                openChat(roomId)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { dismiss() }
                }
            }
        }
    }
}
