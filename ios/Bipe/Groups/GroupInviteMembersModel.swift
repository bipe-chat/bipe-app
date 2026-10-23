// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type PersonPick of spec group-invite-members.
struct PersonPick: Identifiable, Equatable {
    enum Status: Equatable {
        case selectable
        case selected
        case member
        case invited
        case refused
        case failed
    }

    var person: Person
    var status: Status

    var id: String { person.userId }
}

/// Spec group-invite-members (bipe-shared product/screens/group-invite-members.json).
@MainActor
final class GroupInviteMembersModel: ObservableObject {
    enum Mode {
        /// From group-create, with the people it already has. Nothing is sent.
        case pick([Person])
        /// For an existing group.
        case invite(roomId: String)
    }

    enum Phase: Equatable {
        case choosing
        case inviting
        case done
    }

    @Published private(set) var query = ""
    @Published private(set) var contacts: [PersonPick] = []
    @Published private(set) var handleMatch: PersonPick?
    @Published private(set) var handleNotFound = false
    @Published private(set) var phase = Phase.choosing
    @Published private(set) var loading = true

    let mode: Mode
    private let session: AccountSession
    private var people: [Person] = []
    /// The people picked, in the order picked.
    private var selected: [String] = []
    private var memberStatus: [String: PersonPick.Status] = [:]
    private var results: [String: PersonPick.Status] = [:]
    /// Needs the Bipe API client; without it the screen has the SDK contacts only.
    private var lookup: GroupInviteLookup?
    private var lookupTask: Task<Void, Never>?
    /// The holder of the @ in query, from the last lookup.
    private var holder: Person?

    init(session: AccountSession, mode: Mode) {
        self.session = session
        self.mode = mode
        if case .pick(let people) = mode {
            self.people = people
            selected = people.map(\.userId)
        }
    }

    var selectedCount: Int { selected.count }

    func appear(api: BipeAPIClient?, media: MediaLoader) async {
        guard loading else { return }
        if let api {
            lookup = GroupInviteLookup(people: PeopleService(session: session, api: api, media: media))
        }
        var people = await GroupPeople.contacts(client: session.client, ownUserId: session.userId)
        // People picked before stay on the list even when they are not contacts.
        for person in self.people where !people.contains(where: { $0.userId == person.userId }) {
            people.append(person)
        }
        self.people = people
        if case .invite(let roomId) = mode, let room = try? session.client.getRoom(roomId: roomId),
           let members = await GroupPeople.members(of: room) {
            for member in members {
                switch member.membership {
                case .join: memberStatus[member.userId] = .member
                case .invite: memberStatus[member.userId] = .invited
                default: break
                }
            }
        }
        loading = false
        rebuild()
        // Invite pairs and address-book matches come after: the API calls must not hold the list back.
        if let lookup {
            let more = await lookup.moreContacts(except: Set(self.people.map(\.userId)))
            self.people = (self.people + more.filter { person in !self.people.contains { $0.userId == person.userId } })
                .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            rebuild()
        }
    }

    /// Action setQuery: filters contacts at once. A valid @ is looked up after a short pause.
    func setQuery(_ query: String) {
        self.query = query
        lookupTask?.cancel()
        holder = nil
        handleNotFound = false
        rebuild()
        guard let lookup, let handle = GroupInviteLookup.handle(in: query) else { return }
        lookupTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            let found: Person?
            do {
                found = try await lookup.holder(of: handle)
            } catch {
                // A failed lookup shows neither the person nor "not found".
                return
            }
            guard let self, !Task.isCancelled, self.query == query else { return }
            if let found, found.userId != self.session.userId {
                self.holder = found
            } else if found == nil {
                self.handleNotFound = true
            }
            self.rebuild()
        }
    }

    /// Action toggle.
    func toggle(_ userId: String) {
        guard phase != .inviting else { return }
        if let holder, holder.userId == userId, !people.contains(where: { $0.userId == userId }) {
            people.append(holder)
        }
        switch status(of: userId) {
        case .member, .invited:
            return
        case .refused, .failed:
            // Try again: the person is picked once more.
            results[userId] = nil
        case .selectable, .selected:
            break
        }
        phase = .choosing
        if let index = selected.firstIndex(of: userId) {
            selected.remove(at: index)
        } else {
            selected.append(userId)
        }
        rebuild()
    }

    /// The selected people, in the order picked (pick mode).
    var selectedPeople: [Person] {
        selected.compactMap { userId in people.first { $0.userId == userId } }
    }

    /// Action invite, invite mode: one call per person; each row shows its result. Returns true when all succeed.
    func invite() async -> Bool {
        guard case .invite(let roomId) = mode, phase == .choosing, !selected.isEmpty,
              let room = try? session.client.getRoom(roomId: roomId) else { return false }
        phase = .inviting
        var allSent = true
        for userId in selected {
            var failure: Error?
            do {
                try await room.inviteUserById(userId: userId)
            } catch {
                failure = error
            }
            switch InviteOutcome(error: failure) {
            case .sent:
                memberStatus[userId] = .invited
            case .refused:
                results[userId] = .refused
                allSent = false
            case .failed:
                results[userId] = .failed
                allSent = false
            }
            rebuild()
        }
        selected.removeAll()
        phase = .done
        rebuild()
        return allSent
    }

    private func status(of userId: String) -> PersonPick.Status {
        if let result = results[userId] { return result }
        if let status = memberStatus[userId] { return status }
        return selected.contains(userId) ? .selected : .selectable
    }

    private func rebuild() {
        contacts = people
            .filter { $0.matches(query) || results[$0.userId] != nil }
            .map { PersonPick(person: $0, status: status(of: $0.userId)) }
        // The holder of the @ shows on its own row unless the contacts above show the person already.
        handleMatch = holder.flatMap { holder in
            contacts.contains { $0.person.userId == holder.userId } ? nil : PersonPick(person: holder, status: status(of: holder.userId))
        }
    }
}
