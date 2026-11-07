// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Same avatar rules and member-cluster geometry as AvatarView (iOS) and ui/Avatar (web).
struct MacAvatar: View {
    let name: String
    var isCompany = false
    var media: MacMedia? = nil
    var url: String? = nil
    var userID: String? = nil
    var members: [MacAvatarPerson] = []
    var size: CGFloat = 48
    var official = false
    @State private var image: NSImage?
    @State private var resolvedCompany = false

    var body: some View {
        Group {
            if official || BipeOfficialAccounts.isOfficial(userID) {
                ZStack {
                    BipeColor.accentFill
                    BipeMark().fill(BipeColor.inkOnAccent).frame(width: size * 0.58, height: size * 0.58)
                }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous))
            } else if url == nil, !members.isEmpty {
                MacMemberCluster(members: Array(members.prefix(3)), media: media, size: size)
            } else {
                ZStack {
                    BipeColor.accentWash
                    if let image { Image(nsImage: image).resizable().scaledToFill() }
                    else {
                        Text(String(name.trimmingCharacters(in: CharacterSet(charactersIn: "@#!+ ")).prefix(1)).uppercased())
                            .font(.system(size: size * 0.42, weight: .semibold, design: .rounded)).foregroundStyle(BipeColor.accentInk)
                    }
                }
                .frame(width: size, height: size)
                .clipShape(isCompany || resolvedCompany
                           ? AnyShape(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous)) : AnyShape(Circle()))
                .task(id: "\(url ?? "")|\(userID ?? "")|\(size)") {
                    image = nil; resolvedCompany = false
                    guard let media else { return }
                    async let photo = media.avatar(url: url, userID: userID, size: Int(size * 3))
                    async let company = companyType(media)
                    let loaded = await photo
                    guard !Task.isCancelled else { return }
                    image = loaded
                    let square = await company
                    guard !Task.isCancelled else { return }
                    resolvedCompany = square
                }
            }
        }.accessibilityHidden(true)
    }

    private func companyType(_ media: MacMedia) async -> Bool {
        if isCompany { return true }
        guard let userID else { return false }
        return await media.isCompany(userID)
    }
}

private struct MacMemberCluster: View {
    let members: [MacAvatarPerson]
    let media: MacMedia?
    let size: CGFloat
    private static let layouts: [[(diameter: CGFloat, x: CGFloat, y: CGFloat)]] = [
        [(0.56, 0.36, 0.38), (0.48, 0.66, 0.64)],
        [(0.5, 0.36, 0.38), (0.36, 0.72, 0.36), (0.4, 0.6, 0.73)],
    ]
    var body: some View {
        if members.count == 1, let person = members.first {
            MacAvatar(name: person.name, media: media, url: person.avatarURL, userID: person.id, size: size)
        } else {
            let slots = Self.layouts[min(members.count, 3) - 2]
            ZStack {
                BipeColor.surfaceSunken
                ForEach(Array(slots.enumerated()), id: \.offset) { index, slot in
                    let person = members[index]
                    MacAvatar(name: person.name, media: media, url: person.avatarURL, size: size * slot.diameter,
                              official: BipeOfficialAccounts.isOfficial(person.id))
                        .clipShape(Circle())
                        .overlay(Circle().stroke(BipeColor.surfaceSunken, lineWidth: max(1, size * 0.025)))
                        .position(x: size * slot.x, y: size * slot.y)
                }
            }.frame(width: size, height: size).clipShape(Circle())
        }
    }
}
