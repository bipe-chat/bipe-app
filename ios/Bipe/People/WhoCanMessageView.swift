// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec who-can-message: who can start a chat with the user or invite them to a group.
/// The server enforces the choice (ADR 0038); the app only shows and saves it.
struct WhoCanMessageView: View {
    let people: PeopleService

    @State private var current: BipeWhoCanMessage = .everyone
    @State private var saving = false
    @State private var failed = false

    var body: some View {
        List {
            Section {
                ForEach(BipeWhoCanMessage.allCases, id: \.self) { value in
                    Button {
                        Task { await choose(value) }
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(Self.title(value))
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Text(Self.detail(value))
                                    .font(.footnote)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                            Spacer(minLength: 8)
                            if value == current {
                                PhosphorIcon.check.image
                                    .resizable()
                                    .frame(width: 20, height: 20)
                                    .foregroundStyle(BipeColor.accentInk)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(value == current ? .isSelected : [])
                }
            } footer: {
                if failed {
                    Text(BipeStrings.errorNetwork)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
            .disabled(saving)
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.messagingTitle)
        .accessibilityIdentifier(BipeScreen.whoCanMessage.rawValue)
        .task { await appear() }
    }

    static func title(_ value: BipeWhoCanMessage) -> String {
        switch value {
        case .everyone: BipeStrings.messagingEveryone
        case .verified: BipeStrings.messagingVerified
        case .contacts: BipeStrings.messagingContacts
        }
    }

    static func detail(_ value: BipeWhoCanMessage) -> String {
        switch value {
        case .everyone: BipeStrings.messagingEveryoneBody
        case .verified: BipeStrings.messagingVerifiedBody
        case .contacts: BipeStrings.messagingContactsBody
        }
    }

    /// Action appear: GET /v1/me/messaging.
    private func appear() async {
        do {
            current = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
                try await people.api.messagingSetting(accessToken: token)
            }
            failed = false
        } catch {
            failed = true
        }
    }

    /// Action choose: shows the new choice at once and puts the old one back on failure.
    private func choose(_ value: BipeWhoCanMessage) async {
        guard value != current, !saving else { return }
        let old = current
        current = value
        saving = true
        defer { saving = false }
        do {
            current = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
                try await people.api.setMessagingSetting(value, accessToken: token)
            }
            failed = false
        } catch {
            current = old
            failed = true
        }
    }
}
