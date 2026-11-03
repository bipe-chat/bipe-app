// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI
import UIKit

/// Loads avatar thumbnails through the SDK media API and keeps them in memory.
final class MediaLoader {
    let client: Client
    private let cache = NSCache<NSString, UIImage>()
    private let accountTypes = NSCache<NSString, NSNumber>()
    private let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)

    init(client: Client) {
        self.client = client
    }

    func thumbnail(mxcUrl: String, size: Int) async -> UIImage? {
        let key = "\(mxcUrl)#\(size)" as NSString
        if let cached = cache.object(forKey: key) {
            return cached
        }
        guard let source = try? MediaSource.fromUrl(url: mxcUrl),
              let data = try? await client.getMediaThumbnail(mediaSource: source, width: UInt64(size), height: UInt64(size)),
              let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    func avatarUrl(userId: String) async -> String? {
        (try? await client.getProfile(userId: userId))?.avatarUrl
    }

    /// The public account-type endpoint, independent of a CNPJ badge. Unknown accounts stay round (ADR 0132).
    func isCompany(userId: String) async -> Bool {
        if let cached = accountTypes.object(forKey: userId as NSString) { return cached.boolValue }
        guard let type = try? await api.accountType(userID: userId) else { return false }
        let company = type == .company
        accountTypes.setObject(NSNumber(value: company), forKey: userId as NSString)
        return company
    }
}

/// A person's circle or a company's rounded square, with the actual photo or initials (ADR 0132).
struct AvatarView: View {
    let name: String
    let mxcUrl: String?
    let loader: MediaLoader
    var size: CGFloat = 48
    var userId: String? = nil
    var isCompany = false
    var showsPhoto = true
    /// Spec chat-list notes, Group avatar (bipe-chat/bipe#1004): a group with no photo shows these members.
    var members: [Person] = []

    @State private var image: UIImage?
    @State private var resolvedCompany = false

    var body: some View {
        if mxcUrl == nil, !members.isEmpty {
            MemberCluster(members: members, loader: loader, size: size, showsPhotos: showsPhoto)
        } else {
            single
        }
    }

    private var single: some View {
        ZStack {
            BipeColor.accentWash
            if showsPhoto, let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(initial)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(BipeColor.accentInk)
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
        .task(id: "\(userId ?? "")|\(mxcUrl ?? "")|\(size)|\(showsPhoto)") {
            image = nil
            resolvedCompany = false
            async let company = companyType()
            var url = showsPhoto ? mxcUrl : nil
            if showsPhoto, url == nil, let userId { url = await loader.avatarUrl(userId: userId) }
            let loaded: UIImage?
            if let url { loaded = await loader.thumbnail(mxcUrl: url, size: Int(size * 3)) } else { loaded = nil }
            guard !Task.isCancelled else { return }
            image = loaded
            let type = await company
            guard !Task.isCancelled else { return }
            resolvedCompany = type
        }
    }

    private var shape: AnyShape {
        isCompany || resolvedCompany
            ? AnyShape(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous))
            : AnyShape(Circle())
    }

    private func companyType() async -> Bool {
        guard !isCompany, let userId else { return isCompany }
        return await loader.isCompany(userId: userId)
    }

    private var initial: String {
        let letter = name.trimmingCharacters(in: CharacterSet(charactersIn: "@#!+ ")).first
        return letter.map { String($0).uppercased() } ?? ""
    }
}

/// Spec chat-list notes, Group avatar: up to 3 members as overlapping circles inside the group circle.
private struct MemberCluster: View {
    let members: [Person]
    let loader: MediaLoader
    let size: CGFloat
    let showsPhotos: Bool

    /// Diameter and centre of each circle as fractions of the group circle, for 2 and 3 members.
    private static let layouts: [[(diameter: CGFloat, x: CGFloat, y: CGFloat)]] = [
        [(0.56, 0.36, 0.38), (0.48, 0.66, 0.64)],
        [(0.5, 0.36, 0.38), (0.36, 0.72, 0.36), (0.4, 0.6, 0.73)],
    ]

    var body: some View {
        if members.count == 1, let person = members.first {
            AvatarView(name: person.displayName, mxcUrl: person.avatarUrl, loader: loader, size: size, userId: person.userId,
                       showsPhoto: showsPhotos)
        } else {
            let slots = Self.layouts[min(members.count, 3) - 2]
            ZStack {
                BipeColor.surfaceSunken
                ForEach(Array(slots.enumerated()), id: \.offset) { index, slot in
                    let person = members[index]
                    // No userId: the hero already carries the photo, and the circles stay round.
                    AvatarView(name: person.displayName, mxcUrl: person.avatarUrl, loader: loader, size: size * slot.diameter,
                               showsPhoto: showsPhotos)
                        .overlay(Circle().stroke(BipeColor.surfaceSunken, lineWidth: max(1, size * 0.025)))
                        .position(x: size * slot.x, y: size * slot.y)
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            .accessibilityHidden(true)
        }
    }
}

extension Person {
    /// Spec chat-list notes, Group avatar (bipe-chat/bipe#1004): the heroes of a group with no m.room.avatar,
    /// without the own user, at most 3. Empty for a DM or a group with a photo.
    static func groupAvatarMembers(of info: RoomInfo, ownUserId: String) -> [Person] {
        guard !info.isDm, (info.avatarUrl ?? "").isEmpty else { return [] }
        return info.heroes.filter { $0.userId != ownUserId }.prefix(3)
            .map { Person(userId: $0.userId, displayName: $0.displayName, avatarUrl: $0.avatarUrl) }
    }
}
