// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// The group and DM screens over chat-list: group-create and dm-delete, closeChat for group-leave and dm-delete, and
/// the account work of these specs (quiet groups once per account, the cleared-DM markers at start and on each return
/// to the foreground).
private struct GroupHostModifier: ViewModifier {
    let session: AccountSession
    let api: BipeAPIClient
    let media: MediaLoader
    let openChat: (String) -> Void
    let closeChat: () -> Void
    @Binding var creatingGroup: Bool
    @Binding var deletingDm: String?
    @Binding var showingInvites: Bool
    @State private var toast: String?
    @ObservedObject private var cleared = ClearedChatsStore.shared
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $showingInvites) {
                InvitesEntryView(session: session, api: api, media: media) { roomId in
                    openChat(roomId)
                }
            }
            .sheet(isPresented: $creatingGroup) {
                GroupCreateView(session: session, media: media) { roomId, allInvited in
                    openChat(roomId)
                    if !allInvited {
                        toast = BipeStrings.appGroupCreateInvitesFailed
                    }
                }
            }
            .sheet(item: Binding(get: { deletingDm.map(DmSheetId.init) }, set: { deletingDm = $0?.id })) { dm in
                DmDeleteView(session: session, roomId: dm.id) {}
            }
            .toast($toast)
            .task { await QuietGroups.applyOnce(session: session) }
            .task { await cleared.refresh(client: session.client) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task { await cleared.refresh(client: session.client) }
                }
            }
            // Last, so the sheets above get them too.
            .environment(\.closeChat, closeChat)
            // A link to another chat replaces the detail root, as a row does.
            .environment(\.openOtherChat, { roomId in openChat(roomId) })
            .environment(\.bipeAPI, api)
    }
}

private struct DmSheetId: Identifiable {
    let id: String
}

extension View {
    func groupHost(session: AccountSession, api: BipeAPIClient, media: MediaLoader, openChat: @escaping (String) -> Void,
                   closeChat: @escaping () -> Void, creatingGroup: Binding<Bool>, deletingDm: Binding<String?>, showingInvites: Binding<Bool>) -> some View {
        modifier(GroupHostModifier(session: session, api: api, media: media, openChat: openChat, closeChat: closeChat,
                                   creatingGroup: creatingGroup,
                                   deletingDm: deletingDm, showingInvites: showingInvites))
    }
}

/// The plus menu of chat-list: group-create and invites (the Grupos and Você tabs take them over, lane D).
struct CreateGroupButton: View {
    let createGroup: () -> Void
    let invite: () -> Void

    var body: some View {
        Menu {
            Button(BipeStrings.groupsEmptyAction, action: createGroup)
            Button(BipeStrings.invitesTitle, action: invite)
        } label: {
            // Title and image: the system needs the title in the iPhone Duo side bar and in overflow.
            Label {
                Text(BipeStrings.groupsEmptyAction)
            } icon: {
                PhosphorIcon.plus.image.resizable().frame(width: 22, height: 22)
            }
            .labelStyle(.iconOnly)
            .bipeTapTarget()
        }
    }
}
