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
    @Published private(set) var phase = Phase.choosing
    @Published private(set) var loading = true
    @Published private(set) var handleMatch: PersonPick?
    @Published private(set) var handleNotFound = false
    @Published private(set) var lookupFailed = false
    @Published private(set) var lookingUp = false

    let mode: Mode
    private let session: AccountSession
    private var people: [Person] = []
    /// The people picked, in the order picked.
    private var selected: [String] = []
    private var memberStatus: [String: PersonPick.Status] = [:]
    private var results: [String: PersonPick.Status] = [:]
    private var service: PeopleService?
    private var lookupTask: Task<Void, Never>?
    private var lookupVersion: UInt64 = 0
    private var handlePerson: Person?
    private var lookupOnlyIds: Set<String> = []

    init(session: AccountSession, mode: Mode) {
        self.session = session
        self.mode = mode
        if case .pick(let people) = mode {
            self.people = people
            selected = people.map(\.userId)
        }
    }

    var selectedCount: Int { selected.count }

    deinit { lookupTask?.cancel() }

    func appear(api: BipeAPIClient?, media: MediaLoader) async {
        if service == nil, let api {
            service = PeopleService(session: session, api: api, media: media)
            service?.start()
        }
        guard loading else { setQuery(query); return }
        var people = await GroupPeople.contacts(client: session.client, ownUserId: session.userId)
        lookupOnlyIds.subtract(people.map(\.userId))
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
        selected.removeAll { memberStatus[$0] == .member || memberStatus[$0] == .invited }
        setQuery(query)
    }

    /// Filters immediately; only a valid exact @ triggers the existing handle endpoint after a short pause.
    func setQuery(_ query: String) {
        self.query = query
        lookupTask?.cancel()
        lookupVersion &+= 1
        let version = lookupVersion
        handlePerson = nil
        handleNotFound = false
        lookupFailed = false
        lookingUp = false
        rebuild()
        let input = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.hasPrefix("@"), let service else { return }
        let handle = String(input.dropFirst()).lowercased()
        guard BipeHandle.localProblem(for: handle) == nil else { return }
        lookingUp = true
        lookupTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(250))
                let id = try await service.handles.userID(forHandle: handle)
                guard !Task.isCancelled, let self, version == self.lookupVersion else { return }
                guard let id, id != self.session.userId, !service.isBlocked(id) else {
                    self.lookingUp = false
                    self.handleNotFound = true
                    return
                }
                let loaded = (try? await service.person(id)) ?? Person(userId: id, displayName: "@" + handle, handle: handle)
                guard !Task.isCancelled, version == self.lookupVersion else { return }
                if !self.people.contains(where: { $0.userId == id }) {
                    self.people.append(loaded)
                    self.lookupOnlyIds.insert(id)
                } else if let index = self.people.firstIndex(where: { $0.userId == id }) {
                    self.people[index].handle = self.people[index].handle ?? handle
                }
                self.handlePerson = self.people.first { $0.userId == id } ?? loaded
                self.lookingUp = false
                self.rebuild()
            } catch {
                guard !Task.isCancelled, let self, version == self.lookupVersion else { return }
                self.lookingUp = false
                self.lookupFailed = true
            }
        }
    }

    func close() { lookupTask?.cancel(); lookingUp = false }

    /// Action toggle.
    func toggle(_ userId: String) {
        guard phase != .inviting else { return }
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
            .filter { !lookupOnlyIds.contains($0.userId) || selected.contains($0.userId) || results[$0.userId] != nil }
            .filter { $0.userId != handlePerson?.userId }
            .filter { $0.matches(query) || results[$0.userId] != nil }
            .map { PersonPick(person: $0, status: status(of: $0.userId)) }
        handleMatch = handlePerson.map { PersonPick(person: $0, status: status(of: $0.userId)) }
    }
}
