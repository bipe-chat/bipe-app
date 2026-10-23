// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// The Bipe API part of group-invite-members: the invite pairs and address-book matches among the contacts, and the
/// exact @ lookup. The DM partners and group members come from the SDK (GroupPeople.contacts).
@MainActor
struct GroupInviteLookup {
    let people: PeopleService

    /// The handle in query when it is a valid @ (registries/handles.json), else nil.
    static func handle(in query: String) -> String? {
        let handle = BipeHandle.sanitize(query).lowercased()
        guard !handle.isEmpty, BipeHandle.localProblem(for: handle) == nil else { return nil }
        return handle
    }

    /// Invite pairs (GET /v1/me/invite-connections, ADR 0034) and, when contacts access is given already, the
    /// address-book matches (POST /v1/contacts/lookup, ADR 0015). This screen never asks for contacts access.
    /// A failed call leaves its people out.
    func moreContacts(except known: Set<String>) async -> [Person] {
        var names: [String: String?] = [:]
        if let pairs = try? await people.withToken({ (token: String) async throws(BipeAPIError) -> [String] in
            try await people.api.inviteConnections(accessToken: token)
        }) {
            for userId in pairs { names[userId] = String?.none }
        }
        let permission = ContactsModel.currentPermission()
        if permission == .granted || permission == .limited {
            let book = await Task.detached(priority: .userInitiated) { ContactsModel.readAddressBook() }.value
            let phones = Array(book.keys)
            for start in stride(from: 0, to: phones.count, by: ContactsModel.lookupChunk) {
                let chunk = Array(phones[start..<min(start + ContactsModel.lookupChunk, phones.count)])
                guard let found = try? await people.withToken({ (token: String) async throws(BipeAPIError) -> [BipeContactMatch] in
                    try await people.api.lookupContacts(phones: chunk, accessToken: token)
                }) else { break }
                for match in found {
                    names[match.userId] = book[match.phone]
                }
            }
        }
        people.rememberContactNames(names.compactMapValues { $0 })
        var result: [Person] = []
        for (userId, name) in names where userId != people.session.userId && !known.contains(userId) {
            let person = (try? await people.person(userId, contactName: name))
                ?? Person(userId: userId, displayName: name)
            result.append(person)
        }
        return result
    }

    /// GET /v1/handles/{handle}: the holder of the @, or nil when nobody holds it. Throws when the lookup fails.
    func holder(of handle: String) async throws -> Person? {
        guard let userId = try await people.handles.userID(forHandle: handle) else { return nil }
        return (try? await people.person(userId)) ?? Person(userId: userId, displayName: nil, handle: handle)
    }
}
