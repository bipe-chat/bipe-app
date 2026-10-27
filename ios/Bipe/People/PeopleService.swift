// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Contacts
import Foundation
import MatrixRustSDK

/// Type Person of the people specs: the name to show, the photo and the @. Never the Matrix ID (ADR 0013).
struct Person: Identifiable, Equatable, Hashable {
    var userId: String
    /// The saved contact name, else the profile name, else the @, else the ID's localpart.
    var displayName: String
    var avatarUrl: String?
    var handle: String?

    init(userId: String, displayName: String?, avatarUrl: String? = nil, handle: String? = nil) {
        self.userId = userId
        self.displayName = displayName.flatMap { $0.isEmpty ? nil : $0 } ?? Self.localpart(of: userId)
        self.avatarUrl = avatarUrl
        self.handle = handle
    }

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
    /// The @ of Matrix IDs (BipeKit, cached and observable).
    let handles: BipeHandleResolver
    /// Company accounts and their seals, verified on the device (BipeKit, cached and observable).
    let seals: BipeCompanySeals
    /// Brand marks for resellers (ADR 0162), bound to the same master key as the seal.
    let brandMarks: BipeBrandMarks

    /// Client.ignoredUsers, kept current by Client.subscribeToIgnoredUsers.
    @Published private(set) var ignoredUsers: Set<String> = []
    @Published private(set) var ignoredUsersLoaded = false

    /// Names learned from an explicitly permitted contact lookup. Memory only, partitioned by this account.
    /// Request checks use this map locally; opening a request never uploads the address book.
    @Published private(set) var contactNames: [String: String] = [:]

    func rememberContactNames(_ names: [String: String]) {
        guard ContactsModel.currentPermission() == .granted || ContactsModel.currentPermission() == .limited else {
            contactNames = [:]
            return
        }
        contactNames = names.mapValues { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.value.isEmpty }
    }

    func contactName(for userId: String) -> String? {
        guard ContactsModel.currentPermission() == .granted || ContactsModel.currentPermission() == .limited else { return nil }
        return contactNames[userId]
    }

    /// User ID to the address-book entry ID that the contact lookup matched (ADR 0191, spec person-profile savedNumbers).
    /// Memory only, this account. Never a number: person-profile reads the entry again.
    private var contactEntries: [String: String] = [:]
    /// A lookup of the address book ran in this session, so person-profile does not spend the daily budget again.
    private var addressBookLookedUp = false

    func rememberContactEntries(_ entries: [String: String]) {
        guard ContactsModel.currentPermission() == .granted || ContactsModel.currentPermission() == .limited else {
            contactEntries = [:]
            return
        }
        contactEntries = entries
        addressBookLookedUp = true
    }

    /// Spec person-profile savedNumbers (ADR 0191, bipe-chat/bipe#788): the numbers the user saved for this person, from
    /// the user's own address book. Without contacts access or without a match: empty. Without a match in this session,
    /// one lookup of the whole address book (as contacts check), once per session.
    func savedNumbers(of userId: String) async -> [String] {
        guard userId != session.userId else { return [] }
        guard ContactsModel.currentPermission() == .granted || ContactsModel.currentPermission() == .limited else {
            contactEntries = [:]
            return []
        }
        if contactEntries[userId] == nil, !addressBookLookedUp {
            await lookUpAddressBook()
        }
        guard let entryId = contactEntries[userId] else { return [] }
        return await Task.detached(priority: .userInitiated) { ContactsModel.savedNumbers(ofEntry: entryId) }.value
    }

    /// POST /v1/contacts/lookup for the whole address book, in chunks. Keeps the names and entry IDs of the matches.
    /// Stops at the first failure and keeps what it has; never runs twice in a session.
    private func lookUpAddressBook() async {
        addressBookLookedUp = true
        let book = await Task.detached(priority: .userInitiated) { ContactsModel.readAddressBook() }.value
        let phones = Array(book.keys)
        var names: [String: String] = [:]
        var entries: [String: String] = [:]
        let api = api
        for start in stride(from: 0, to: phones.count, by: ContactsModel.lookupChunk) {
            let chunk = Array(phones[start..<min(start + ContactsModel.lookupChunk, phones.count)])
            guard let found = try? await withToken({ (token: String) async throws(BipeAPIError) -> [BipeContactMatch] in
                try await api.lookupContacts(phones: chunk, accessToken: token)
            }) else { break }
            for match in found where match.userId != session.userId && entries[match.userId] == nil {
                guard let entry = book[match.phone] else { continue }
                names[match.userId] = entry.name
                entries[match.userId] = entry.id
            }
        }
        rememberContactNames(contactNames.merging(names) { _, new in new })
        rememberContactEntries(entries)
    }

    private var ignoredHandle: TaskHandle?
    private var ignoredTask: Task<Void, Never>?

    init(session: AccountSession, api: BipeAPIClient, media: MediaLoader) {
        self.session = session
        self.api = api
        self.media = media
        handles = BipeHandleResolver(apiClient: api, accessToken: { session.accessToken() })
        // The account's cross-signing master key from the SDK (UserIdentity.masterKey) binds a cnpj badge.
        seals = BipeCompanySeals(apiClient: api, sessionUserID: session.userId) { userId in
            try await session.client.encryption().userIdentity(userId: userId, fallbackToServer: true)?.masterKey()
        }
        brandMarks = BipeBrandMarks(apiClient: api, sessionUserID: session.userId) { userId in
            try await session.client.encryption().userIdentity(userId: userId, fallbackToServer: true)?.masterKey()
        }
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

    // MARK: - Profile facts (ADR 0049)

    /// Facts read from POST /v1/profile-facts per person, with the time of the read. Memory only, this account.
    private var profileFactsCache: [String: (read: Date, facts: BipeProfileFacts?)] = [:]

    /// The profile change facts of a person. A read younger than `maxAge` comes from memory.
    /// nil when the call fails or the API knows nothing: the screens then show nothing.
    func profileFacts(_ userId: String, maxAge: TimeInterval = 0) async -> BipeProfileFacts? {
        if let cached = profileFactsCache[userId], Date().timeIntervalSince(cached.read) < maxAge {
            return cached.facts
        }
        let api = api
        guard let list = try? await withToken({ (token: String) async throws(BipeAPIError) -> [BipeProfileFacts] in
            try await api.profileFacts(userIDs: [userId], accessToken: token)
        }) else { return nil }
        let facts = list.first { $0.userId == userId }
        profileFactsCache[userId] = (Date(), facts)
        return facts
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

    /// The person's cross-signing identity for the trust facts (ADR 0074). nil: the SDK could not read it.
    func trustIdentity(_ userId: String) async -> BipeTrustSources.Identity? {
        guard let identity = try? await session.client.encryption().userIdentity(userId: userId, fallbackToServer: true) else { return nil }
        return BipeTrustSources.Identity(masterKey: identity.masterKey(), isVerified: identity.isVerified(),
                                         hasVerificationViolation: identity.hasVerificationViolation())
    }

    // MARK: - DMs (ADR 0141)

    /// The other person of a DM (RoomInfo.isDm, ADR 0141), else `nil`.
    func dmPartner(roomId: String) async -> String? {
        guard let room = try? session.client.getRoom(roomId: roomId), let info = try? await room.roomInfo(), info.isDm else { return nil }
        return info.heroes.map(\.userId).first { $0 != session.userId }
    }

    /// The same newest eligible local DM that the list shows (ADR 0141).
    func dmRoomId(with userId: String) async -> String? {
        try? await NativeDmSelection.roomId(with: userId, session: session)
    }

    /// The DM with the person, created when missing: encrypted, trusted private chat, power levels that let
    /// neither person invite or change the room (ADR 0141).
    func openOrCreateDM(with userId: String) async throws -> String {
        if let existing = try await NativeDmSelection.roomId(with: userId, session: session) {
            return existing
        }
        return try await createDM(with: userId)
    }

    /// A new DM with the person (spec person-profile notes). The user is at BipeDmPowerLevels.createLevel and the
    /// person at 0 while the server writes the first state and the invite; then the DM power levels of
    /// registries/dm-power-levels.json put both at 0. A failed second write leaves a usable DM.
    func createDM(with userId: String) async throws -> String {
        let me = session.userId
        let powerLevels = PowerLevels(usersDefault: nil, eventsDefault: nil, stateDefault: nil,
                                      ban: nil, kick: nil, redact: nil, invite: nil, notifications: nil,
                                      users: [me: Int32(BipeDmPowerLevels.createLevel), userId: Int32(BipeDmPowerLevels.usersDefault)],
                                      events: [:])
        let parameters = CreateRoomParameters(name: nil, isEncrypted: true, isDirect: true,
                                              visibility: .private, preset: .trustedPrivateChat,
                                              invite: [userId], powerLevelContentOverride: powerLevels)
        let roomId: String
        do {
            roomId = try await session.client.createRoom(request: parameters)
        } catch let error as ClientError {
            // The person's who-can-message setting refused the invite (ADR 0038).
            if case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error {
                throw PeopleError.inviteRefused
            }
            throw error
        }
        await lockDM(roomId: roomId, userIds: [me, userId])
        return roomId
    }

    /// Room.sendStateEventRaw with the m.room.power_levels content of a new DM (ADR 0141, bipe-chat/bipe#532).
    private func lockDM(roomId: String, userIds: [String]) async {
        for attempt in 1...5 {
            if let room = try? session.client.getRoom(roomId: roomId) {
                _ = try? await room.sendStateEventRaw(eventType: "m.room.power_levels", stateKey: "",
                                                      content: BipeDmPowerLevels.content(userIds: userIds))
                return
            }
            // The created room reaches the local store with the next sync.
            try? await Task.sleep(nanoseconds: UInt64(attempt) * 300_000_000)
        }
    }
}

extension PeopleService {
    /// The dm-delete write (bipe-chat/bipe#464, registries/cleared-chats.json): merges this room's marker, the
    /// origin_server_ts of its latest remote event, into the account data chat.bipe.cleared_chats. Keeps every other
    /// entry; the marker never moves back. The room is not left, so the other person's next message brings it back.
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
    case profileQr
    case contacts
    case whoCanMessage
    case blockedList
}

/// A bipe.chat/@handle link waiting for profile-link-open.
struct HandleLink: Identifiable {
    let handle: String
    var id: String { handle }
}
