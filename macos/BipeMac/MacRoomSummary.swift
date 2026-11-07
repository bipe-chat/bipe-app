// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

struct MacAvatarPerson: Identifiable {
    let id: String
    let name: String
    let avatarURL: String?
}

struct MacAvatarDescriptor {
    let url: String?
    let userID: String?
    let members: [MacAvatarPerson]

    static func room(isDm: Bool, photo: String?, heroes: [MacAvatarPerson], ownUserID: String) -> Self {
        let others = heroes.filter { $0.id != ownUserID }
        if isDm {
            let peer = others.first
            // A DM's room avatar is never the person's photo (ADR 0141).
            return Self(url: peer?.avatarURL, userID: peer?.id, members: [])
        }
        let photo = photo.flatMap { $0.isEmpty ? nil : $0 }
        return Self(url: photo, userID: nil, members: photo == nil ? Array(others.prefix(3)) : [])
    }
}

struct MacRoomAttention {
    let isDm: Bool
    let unread: UInt64
    let markedUnread: Bool
    let mentions: UInt64
    var isUnread: Bool { unread > 0 || markedUnread }
}

struct MacTabAttention {
    var chats = 0
    var groups = 0
    init(_ rooms: [MacRoomAttention]) {
        for room in rooms {
            if room.isDm { if room.isUnread { chats += 1 } }
            else { groups += Int(clamping: room.mentions) }
        }
    }
}

struct MacRoomSummary: Identifiable {
    let room: Room
    let name: String
    let isDm: Bool
    let unread: UInt64
    let markedUnread: Bool
    let mentions: UInt64
    let isFavourite: Bool
    let isArchived: Bool
    let isMuted: Bool
    let avatar: MacAvatarDescriptor
    let activity: BipeRowActivity.Row
    var id: String { room.id() }
    var attention: MacRoomAttention { MacRoomAttention(isDm: isDm, unread: unread, markedUnread: markedUnread, mentions: mentions) }
    var date: Date? { activity.timestampMs.map { Date(timeIntervalSince1970: Double($0) / 1000) } }

    init(room: Room, info: RoomInfo, ownUserID: String, activity: BipeRowActivity.Row, favourite: Bool? = nil) {
        self.room = room
        self.activity = activity
        isDm = info.isDm
        unread = info.numUnreadMessages; markedUnread = info.isMarkedUnread; mentions = info.numUnreadMentions
        isFavourite = favourite ?? info.isFavourite
        isArchived = info.isLowPriority
        isMuted = info.cachedUserDefinedNotificationMode == .mute
        let peer = info.isDm ? info.heroes.first { $0.userId != ownUserID } : nil
        let heroes: [(userID: String, displayName: String?)]? = !info.isDm && info.rawName == nil
            ? info.heroes.filter { $0.userId != ownUserID }.map { ($0.userId, $0.displayName) } : nil
        name = BipeChatRowName.resolve(roomID: info.id, sdkName: info.displayName, peerName: peer?.displayName,
                                      peerID: peer?.userId, unnamed: BipeStrings.dmNoName,
                                      dmRoomName: info.isDm ? info.rawName : nil, groupHeroes: heroes)
        avatar = .room(isDm: info.isDm, photo: info.avatarUrl, heroes: info.heroes.map {
            MacAvatarPerson(id: $0.userId, name: BipeChatRowName.person(userID: $0.userId, displayName: $0.displayName, handle: nil), avatarURL: $0.avatarUrl)
        }, ownUserID: ownUserID)
    }

    static func personName(_ profile: ProfileDetails, userID: String) -> String {
        var displayName: String?
        if case .ready(let name, _, _, _, _) = profile { displayName = name }
        return BipeChatRowName.person(userID: userID, displayName: displayName, handle: nil)
    }

    /// Spec chat-list, row text: "You: " before an own message, the sender's name before another member's in a group.
    static func activity(_ latest: LatestEventValue, isDm: Bool, known: BipeRowActivity.Row?) -> BipeRowActivity.Row {
        let sdk: BipeRowActivity.SDKLatest?
        switch latest {
        case .remote(let timestamp, let sender, let own, let profile, let content):
            let text = preview(content).map { text in
                if own { return BipeStrings.chatListOwnPreview(text) }
                return isDm ? text : "\(personName(profile, userID: sender)): \(text)"
            }
            sdk = .init(timestampMs: timestamp, previewText: text, isIncoming: !own)
        case .local(let timestamp, _, _, let content, _):
            sdk = .init(timestampMs: timestamp, previewText: preview(content).map(BipeStrings.chatListOwnPreview), isIncoming: false)
        case .remoteInvite(let timestamp, _, _): sdk = .init(timestampMs: timestamp, previewText: nil, isIncoming: false)
        case .none: sdk = nil
        }
        return BipeRowActivity.resolve(sdk: sdk, localEvents: [], known: known)
    }

    private static func preview(_ content: TimelineItemContent) -> String? {
        guard case .msgLike(let content) = content else { return nil }
        switch content.kind {
        case .message(let message):
            switch message.msgType {
            case .image: return BipeStrings.appMediaImage
            case .video: return BipeStrings.appMediaVideo
            case .file: return BipeStrings.appMediaFile
            case .audio: return BipeStrings.appMediaVoice
            case .location: return BipeStrings.appLocationTitle
            default: return message.body
            }
        case .sticker: return BipeStrings.appMediaSticker
        case .unableToDecrypt: return BipeStrings.appTimelineUndecryptable
        case .liveLocation: return BipeStrings.appLocationLiveTitle
        default: return nil
        }
    }
}
