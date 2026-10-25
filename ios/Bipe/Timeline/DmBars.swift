// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The composer place of a DM (ADR 0141). A blocked person's DM stays, and the bar of spec block-person
/// replaces the composer: bipe_app_blocked_bar with bipe_action_unblock_person. Unblock opens block-person.
struct DmComposerSlot<Composer: View>: View {
    @ObservedObject var people: PeopleService
    let peerUserId: String
    let name: String
    @ViewBuilder let composer: () -> Composer
    @State private var unblocking = false

    var body: some View {
        Group {
            if people.isBlocked(peerUserId) {
                VStack(spacing: 8) {
                    Text(BipeStrings.appBlockedBar(name))
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                    Button(BipeStrings.actionUnblockPerson) { unblocking = true }
                        .font(.subheadline.weight(.semibold))
                        .tint(BipeColor.accentInk)
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
                .accessibilityElement(children: .contain)
            } else {
                composer()
            }
        }
        .onAppear { people.start() }
        .sheet(isPresented: $unblocking) {
            BlockPersonSheet(people: people, userId: peerUserId, name: name)
        }
    }
}
