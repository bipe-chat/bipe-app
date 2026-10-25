// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec blocked-list: the people the user blocked, with unblock on each row. Opens from Privacidade.
struct BlockedListView: View {
    @ObservedObject var people: PeopleService
    /// Action openPerson.
    let openPerson: (String) -> Void

    @State private var list: [Person] = []
    @State private var loading = true
    @State private var unblocking: Person?

    var body: some View {
        Group {
            if loading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if list.isEmpty {
                Text(BipeStrings.privacyBlockedNone)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(list) { person in
                    HStack(spacing: 12) {
                        Button {
                            openPerson(person.userId)
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(name: person.displayName, mxcUrl: person.avatarUrl, loader: people.media, size: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(person.displayName)
                                        .foregroundStyle(BipeColor.inkPrimary)
                                        .lineLimit(1)
                                    if let handle = person.handle {
                                        Text("@\(handle)")
                                            .font(.caption)
                                            .foregroundStyle(BipeColor.inkSecondary)
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        Spacer(minLength: 8)
                        Button(BipeStrings.actionUnblockPerson) {
                            unblocking = person
                        }
                        .buttonStyle(.bipeBordered)
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.privacyBlocked)
        .accessibilityIdentifier(BipeScreen.blockedList.rawValue)
        .onAppear { people.start() }
        .task(id: "\(people.ignoredUsersLoaded) \(people.ignoredUsers.sorted())") { await reload() }
        .sheet(item: $unblocking) { person in
            BlockPersonSheet(people: people, userId: person.userId, name: person.displayName)
        }
    }

    /// Action appear, and again on each change of Client.ignoredUsers.
    private func reload() async {
        guard people.ignoredUsersLoaded else { return }
        let loaded = await people.people(Array(people.ignoredUsers))
        list = loaded.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        loading = false
    }
}
