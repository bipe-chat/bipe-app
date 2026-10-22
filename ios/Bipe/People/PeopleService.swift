// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The people screens use Person of lane G (Groups/GroupPeople.swift): the name to show, the photo and the @.
extension Person {
    /// The localpart of a Matrix ID, the last fallback of a name (blocked-list notes).
    static func localpart(of userId: String) -> String {
        let trimmed = userId.hasPrefix("@") ? String(userId.dropFirst()) : userId
        return trimmed.split(separator: ":", maxSplits: 1).first.map(String.init) ?? trimmed
    }
}

/// People and profiles for one account (bipe-chat/bipe#568): profiles with their @, the block list,
/// company accounts and seals, and the DM with a person. Screens share one instance per account.
@MainActor
final class PeopleService: ObservableObject {
    let session: AccountSession
    let api: BipeAPIClient
    let media: MediaLoader
    /// The @ of Matrix IDs (BipeKit, cached and observable).
    let handles: BipeHandleResolver
    /// Company accounts and their seals, verified on the device (BipeKit, cached and observable).
    let seals: BipeCompanySeals

    /// Client.ignoredUsers, kept current by Client.subscribeToIgnoredUsers.
    @Published private(set) var ignoredUsers: Set<String> = []
    @Published private(set) var ignoredUsersLoaded = false

    private var ignoredHandle: TaskHandle?
    private var ignoredTask: Task<Void, Never>?

    init(session: AccountSession, api: BipeAPIClient, media: MediaLoader) {
        self.session = session
        self.api = api
        self.media = media
        let serverName = Self.serverName(of: session.userId)
        handles = BipeHandleResolver(apiClient: api, serverName: serverName, accessToken: { session.accessToken() })
        // The account's cross-signing master key from the SDK (UserIdentity.masterKey) binds a cnpj badge.
        seals = BipeCompanySeals(apiClient: api, serverName: serverName, sessionUserID: session.userId) { userId in
            try await session.client.encryption().userIdentity(userId: userId, fallbackToServer: true)?.masterKey()
        }
    }

    /// The server part of a Matrix ID: `bipe.net.br` in `@k7q2m9x4tp:bipe.net.br`.
    static func serverName(of userId: String) -> String {
        userId.split(separator: ":", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    }

    deinit {
        ignoredHandle?.cancel()
        ignoredTask?.cancel()
    }

    /// Starts the ignored-users subscription once. Any screen may call it; the service keeps it for its life.
    func start() {
        guard ignoredTask == nil else { return }
        let stream = IgnoredUsersStream()
        ignoredHandle = session.client.subscribeToIgnoredUsers(listener: stream)
        ignoredTask = Task { [weak self] in
            if let current = try? await self?.session.client.ignoredUsers() {
                self?.ignoredUsers = Set(current)
            }
            self?.ignoredUsersLoaded = true
            for await ids in stream.updates {
                self?.ignoredUsers = Set(ids)
                self?.ignoredUsersLoaded = true
            }
        }
    }

    func isBlocked(_ userId: String) -> Bool {
        ignoredUsers.contains(userId)
    }

    /// Client.ignoreUser. The subscription updates every screen.
    func block(_ userId: String) async throws {
        try await session.client.ignoreUser(userId: userId)
        ignoredUsers.insert(userId)
    }

    /// Client.unignoreUser.
    func unblock(_ userId: String) async throws {
        try await session.client.unignoreUser(userId: userId)
        ignoredUsers.remove(userId)
    }

    /// Calls bipe-api with the account's token, retrying once on 401.
    func withToken<T>(_ request: (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, request)
    }

    // MARK: - People

    /// A person: name and photo from Client.getProfile, the @ from bipe-api. `contactName` wins as the name.
    /// Throws only when neither the profile nor the @ can be read.
    func person(_ userId: String, contactName: String? = nil) async throws -> Person {
        let loadedProfile = try? await session.client.getProfile(userId: userId)
        let loadedHandle = await handles.resolve(userId)
        if loadedProfile == nil, loadedHandle == nil, contactName == nil {
            throw PeopleError.notFound
        }
        return Person(userId: userId,
                      displayName: Self.displayName(contactName: contactName, profileName: loadedProfile?.displayName,
                                                    handle: loadedHandle, userId: userId),
                      avatarUrl: loadedProfile?.avatarUrl,
                      handle: loadedHandle)
    }

    /// Several people at once. A profile that does not load still gives a row (the @ or the localpart).
    func people(_ userIds: [String]) async -> [Person] {
        var result: [Person] = []
        for userId in userIds {
            let profile = try? await session.client.getProfile(userId: userId)
            let handle = await handles.resolve(userId)
            result.append(Person(userId: userId,
                                 displayName: Self.displayName(contactName: nil, profileName: profile?.displayName,
                                                               handle: handle, userId: userId),
                                 avatarUrl: profile?.avatarUrl,
                                 handle: handle))
        }
        return result
    }

    static func displayName(contactName: String?, profileName: String?, handle: String?, userId: String) -> String {
        if let contactName = contactName?.trimmingCharacters(in: .whitespacesAndNewlines), !contactName.isEmpty {
            return contactName
        }
        // A profile name that is the raw Matrix ID is no name (ADR 0013).
        if let profileName = profileName?.trimmingCharacters(in: .whitespacesAndNewlines), !profileName.isEmpty, profileName != userId {
            return profileName
        }
        if let handle {
            return "@\(handle)"
        }
        return Person.localpart(of: userId)
    }

    /// The person's identity is verified by the user (an in-person QR or emoji check).
    func isConfirmedInPerson(_ userId: String) async -> Bool {
        guard let identity = try? await session.client.encryption().userIdentity(userId: userId, fallbackToServer: false) else { return false }
        return identity.isVerified()
    }

    // MARK: - DMs (ADR 0141)

    /// The other person of a DM (RoomInfo.isDm, ADR 0141), else `nil`.
    func dmPartner(roomId: String) async -> String? {
        guard let room = try? session.client.getRoom(roomId: roomId), let info = try? await room.roomInfo(), info.isDm else { return nil }
        return info.heroes.map(\.userId).first { $0 != session.userId }
    }

    /// Client.getDmRoom: the newest DM with the person.
    func dmRoomId(with userId: String) -> String? {
        guard let room = try? session.client.getDmRoom(userId: userId) else { return nil }
        return room.id()
    }

    /// The DM with the person, created when missing: encrypted, trusted private chat, power levels that let
    /// neither person invite or change the room (ADR 0141).
    func openOrCreateDM(with userId: String) async throws -> String {
        if let existing = dmRoomId(with: userId) {
            return existing
        }
        let me = session.userId
        let locked: Int32 = 101
        let powerLevels = PowerLevels(usersDefault: nil, eventsDefault: nil, stateDefault: locked,
                                      ban: nil, kick: nil, redact: nil, invite: locked, notifications: nil,
                                      users: [me: 100, userId: 100],
                                      events: ["m.room.name": locked, "m.room.avatar": locked, "m.room.topic": locked])
        let parameters = CreateRoomParameters(name: nil, isEncrypted: true, isDirect: true,
                                              visibility: .private, preset: .trustedPrivateChat,
                                              invite: [userId], powerLevelContentOverride: powerLevels)
        do {
            return try await session.client.createRoom(request: parameters)
        } catch let error as ClientError {
            // The person's who-can-message setting refused the invite (ADR 0038).
            if case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error {
                throw PeopleError.inviteRefused
            }
            throw error
        }
    }
}

extension PeopleService {
    /// The dm-delete write for report-chat's "delete the chat too": lane G's DmDeleteModel (spec dm-delete).
    /// The room is not left, so the other person's next message brings it back.
    func clearDm(_ roomId: String) async throws {
        guard await DmDeleteModel(session: session, roomId: roomId).delete() else { throw PeopleError.notFound }
    }
}

enum PeopleError: Error {
    case notFound
    /// The other person's who-can-message setting refused the invite.
    case inviteRefused
}

final class IgnoredUsersStream: IgnoredUsersListener {
    let updates: AsyncStream<[String]>
    private let continuation: AsyncStream<[String]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func call(ignoredUserIds: [String]) {
        continuation.yield(ignoredUserIds)
    }
}

/// The people screens a navigation stack can push.
enum PeopleRoute: Hashable {
    case person(userId: String, fromDm: Bool)
    case profileEdit
    case publicProfile
    case profileLink
    case contacts
    case whoCanMessage
    case blockedList
}

/// A bipe.chat/@handle link waiting for profile-link-open.
struct HandleLink: Identifiable {
    let handle: String
    var id: String { handle }
}
