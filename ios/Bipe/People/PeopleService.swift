// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type Person of the people specs: the name to show, the photo and the @. Never the Matrix ID (ADR 0013).
struct Person: Identifiable, Equatable, Hashable {
    var userId: String
    /// The saved contact name, else the profile name, else the @, else the ID's localpart.
    var displayName: String
    var avatarUrl: String?
    var handle: String?

    var id: String { userId }

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
    let handles: BipeHandleResolver

    /// Client.ignoredUsers, kept current by Client.subscribeToIgnoredUsers.
    @Published private(set) var ignoredUsers: Set<String> = []
    @Published private(set) var ignoredUsersLoaded = false

    private var accountTypes: [String: BipeAccountType] = [:]
    private var ignoredHandle: TaskHandle?
    private var started = false

    init(session: AccountSession, api: BipeAPIClient, media: MediaLoader) {
        self.session = session
        self.api = api
        self.media = media
        handles = BipeHandleResolver(api: api, accessToken: { session.accessToken() })
    }

    deinit {
        ignoredHandle?.cancel()
    }

    /// Starts the ignored-users subscription once.
    func start() async {
        guard !started else { return }
        started = true
        let stream = IgnoredUsersStream()
        ignoredHandle = session.client.subscribeToIgnoredUsers(listener: stream)
        if let current = try? await session.client.ignoredUsers() {
            ignoredUsers = Set(current)
        }
        ignoredUsersLoaded = true
        for await ids in stream.updates {
            ignoredUsers = Set(ids)
            ignoredUsersLoaded = true
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
        let loadedHandle = await handles.handle(for: userId)
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
        let resolved = await handles.handles(for: userIds)
        var result: [Person] = []
        for userId in userIds {
            let profile = try? await session.client.getProfile(userId: userId)
            result.append(Person(userId: userId,
                                 displayName: Self.displayName(contactName: nil, profileName: profile?.displayName,
                                                               handle: resolved[userId], userId: userId),
                                 avatarUrl: profile?.avatarUrl,
                                 handle: resolved[userId]))
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

    /// GET /v1/accounts/{userId}/type, cached. A failure is a person (fail closed, ADR 0132).
    func accountType(_ userId: String) async -> BipeAccountType {
        if let cached = accountTypes[userId] {
            return cached
        }
        let type = await api.accountType(userID: userId)
        accountTypes[userId] = type
        return type
    }

    /// The seal of a company account: GET /v1/badges/{userId}, verified on the device against the account's
    /// cross-signing master key (UserIdentity.masterKey). Any failure is no seal.
    func sealState(_ userId: String) async -> BipeCompanySealState {
        guard let lookup = try? await api.badge(userID: userId) else { return .noSeal }
        var masterKey: String?
        if case .envelope = lookup {
            let identity = try? await session.client.encryption().userIdentity(userId: userId, fallbackToServer: true)
            masterKey = identity?.masterKey()
        }
        return BipeCompanySeals.state(of: lookup, userID: userId, masterKey: masterKey)
    }

    /// The person's identity is verified by the user (an in-person QR or emoji check).
    func isConfirmedInPerson(_ userId: String) async -> Bool {
        guard let identity = try? await session.client.encryption().userIdentity(userId: userId, fallbackToServer: false) else { return false }
        return identity.isVerified()
    }

    // MARK: - DMs (ADR 0141)

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
