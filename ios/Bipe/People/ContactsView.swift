// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Contacts
import Phosphor
import SwiftUI
import UIKit

/// One address-book entry for a normalised number: the saved name and the entry ID (`CNContact.identifier`).
/// The entry ID lets person-profile read the entry again; the app keeps no copy of the numbers (ADR 0191).
struct AddressBookEntry: Sendable, Equatable {
    var name: String
    var id: String
}

/// Type ContactMatch of spec contacts.
struct ContactMatch: Identifiable, Equatable {
    var person: Person
    var joinedWithInvite: Bool

    var id: String { person.userId }
}

/// Spec contacts (bipe-shared product/screens/contacts.json). The numbers leave the phone only for the lookup;
/// the server compares them and keeps none (ADR 0015). The app keeps no copy of what it sent.
@MainActor
final class ContactsModel: ObservableObject {
    enum Permission: Equatable {
        case notAsked
        case granted
        case denied
        case limited
    }

    enum Phase: Equatable {
        case idle
        case checking
        case loaded
        case failed(rateLimited: Bool)
    }

    /// Most numbers per POST /v1/contacts/lookup.
    static let lookupChunk = 500

    let people: PeopleService
    @Published private(set) var permission: Permission = .notAsked
    @Published private(set) var matches: [ContactMatch] = []
    @Published private(set) var phase: Phase = .idle

    init(people: PeopleService) {
        self.people = people
        permission = Self.currentPermission()
    }

    static func currentPermission() -> Permission {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .notDetermined: .notAsked
        case .authorized: .granted
        case .denied, .restricted: .denied
        default: .limited
        }
    }

    var canRead: Bool {
        permission == .granted || permission == .limited
    }

    /// Action appear: checks when access is there already.
    func appear() async {
        permission = Self.currentPermission()
        if canRead, phase == .idle {
            await check()
        }
    }

    /// Action allow.
    func allow() async {
        _ = try? await CNContactStore().requestAccess(for: .contacts)
        permission = Self.currentPermission()
        if canRead {
            await check()
        }
    }

    /// Action openSettings.
    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    /// Action check: normalised numbers in chunks of 500 to POST /v1/contacts/lookup. Stops at a 429.
    func check() async {
        guard canRead, phase != .checking else { return }
        phase = .checking
        let book = await Task.detached(priority: .userInitiated) { Self.readAddressBook() }.value
        var found: [String: String] = [:]
        var entries: [String: String] = [:]
        let phones = Array(book.keys)
        do throws(BipeAPIError) {
            for start in stride(from: 0, to: phones.count, by: Self.lookupChunk) {
                let chunk = Array(phones[start..<min(start + Self.lookupChunk, phones.count)])
                let result = try await people.withToken { (token: String) async throws(BipeAPIError) -> [BipeContactMatch] in
                    try await people.api.lookupContacts(phones: chunk, accessToken: token)
                }
                for match in result where match.userId != people.session.userId && found[match.userId] == nil {
                    guard let entry = book[match.phone] else { continue }
                    found[match.userId] = entry.name
                    entries[match.userId] = entry.id
                }
            }
        } catch {
            if case .server(.rateLimited, _, _) = error {
                phase = .failed(rateLimited: true)
            } else {
                phase = .failed(rateLimited: false)
            }
            return
        }
        people.rememberContactNames(found)
        people.rememberContactEntries(entries)
        let invited = (try? await people.withToken { (token: String) async throws(BipeAPIError) -> Set<String> in
            Set(try await people.api.inviteConnections(accessToken: token))
        }) ?? []
        var list: [ContactMatch] = []
        for (userId, name) in found {
            let person = (try? await people.person(userId, contactName: name))
                ?? Person(userId: userId, displayName: name, avatarUrl: nil)
            list.append(ContactMatch(person: person, joinedWithInvite: invited.contains(userId)))
        }
        matches = list.sorted { $0.person.displayName.localizedCaseInsensitiveCompare($1.person.displayName) == .orderedAscending }
        phase = .loaded
    }

    /// E.164 to the address-book entry that holds it. Numbers that do not normalise and duplicates are dropped.
    nonisolated static func readAddressBook() -> [String: AddressBookEntry] {
        let store = CNContactStore()
        let keys: [CNKeyDescriptor] = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName), CNContactPhoneNumbersKey as CNKeyDescriptor]
        let request = CNContactFetchRequest(keysToFetch: keys)
        var entries: [String: AddressBookEntry] = [:]
        try? store.enumerateContacts(with: request) { contact, _ in
            let name = CNContactFormatter.string(from: contact, style: .fullName) ?? ""
            for number in contact.phoneNumbers {
                if let e164 = BipePhoneNumber.normalize(number.value.stringValue), entries[e164] == nil {
                    entries[e164] = AddressBookEntry(name: name, id: contact.identifier)
                }
            }
        }
        return entries
    }

    /// Spec person-profile savedNumbers (ADR 0191): the numbers of one address-book entry, read again now, formatted for
    /// reading, without duplicates. Empty when the entry is gone or access is not given.
    nonisolated static func savedNumbers(ofEntry id: String) -> [String] {
        let keys: [CNKeyDescriptor] = [CNContactPhoneNumbersKey as CNKeyDescriptor]
        guard let contact = try? CNContactStore().unifiedContact(withIdentifier: id, keysToFetch: keys) else { return [] }
        var numbers: [String] = []
        for number in contact.phoneNumbers {
            let shown = BipePhoneNumber.displaySaved(number.value.stringValue)
            if !shown.isEmpty, !numbers.contains(shown) {
                numbers.append(shown)
            }
        }
        return numbers
    }
}

/// Spec contacts: which people in the address book are on Bipe, and an invite for the others.
struct ContactsView: View {
    @StateObject private var model: ContactsModel
    /// Action openChat: the DM, created when missing.
    let openChat: (String) -> Void
    /// Action invite: opens invites. `nil` hides the row until the invites screen exists.
    let invite: (() -> Void)?
    @State private var notice: String?

    init(people: PeopleService, openChat: @escaping (String) -> Void, invite: (() -> Void)?) {
        _model = StateObject(wrappedValue: ContactsModel(people: people))
        self.openChat = openChat
        self.invite = invite
    }

    var body: some View {
        List {
            switch model.permission {
            case .notAsked:
                Section {
                    Text(BipeStrings.contactsIntro)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Button(BipeStrings.contactsAllow) { Task { await model.allow() } }
                }
            case .denied:
                Section {
                    Text(BipeStrings.contactsDenied)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Button(BipeStrings.contactsOpenSettings) { model.openSettings() }
                }
            case .granted, .limited:
                results
            }
            if let invite {
                Section {
                    Button(action: invite) {
                        IconLabel(icon: .userPlus, text: BipeStrings.contactsInvite)
                    }
                }
            }
        }
        .navigationTitle(BipeStrings.contactsTitle)
        .accessibilityIdentifier(BipeScreen.contacts.rawValue)
        .refreshable { await model.check() }
        .toast($notice)
        .task { await model.appear() }
    }

    @ViewBuilder
    private var results: some View {
        switch model.phase {
        case .idle, .checking:
            Section {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(BipeStrings.appContactsLoading)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
        case .failed(let rateLimited):
            Section {
                Text(rateLimited ? BipeStrings.errorRateLimited : BipeStrings.errorNetwork)
                    .foregroundStyle(BipeColor.trustDangerInk)
                if !rateLimited {
                    Button(BipeStrings.appRetry) { Task { await model.check() } }
                }
            }
        case .loaded:
            if model.matches.isEmpty {
                Section {
                    Text(BipeStrings.contactsNone)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            } else {
                Section {
                    ForEach(model.matches) { match in
                        row(match)
                    }
                }
            }
        }
    }

    private func row(_ match: ContactMatch) -> some View {
        HStack(spacing: 12) {
            AvatarView(name: match.person.displayName, mxcUrl: match.person.avatarUrl, loader: model.people.media, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(match.person.displayName)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                if match.joinedWithInvite {
                    Text(BipeStrings.contactsJoinedWithInvite)
                        .font(.caption)
                        .foregroundStyle(BipeColor.accentInk)
                } else if let handle = match.person.handle {
                    Text("@\(handle)")
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer(minLength: 8)
            Button(BipeStrings.invitesChat) {
                Task {
                    do {
                        openChat(try await model.people.openOrCreateDM(with: match.person.userId))
                    } catch PeopleError.inviteRefused {
                        notice = BipeStrings.appGroupInviteRefused(match.person.displayName)
                    } catch {
                        notice = BipeStrings.errorGeneric
                    }
                }
            }
            .buttonStyle(.bipeBordered)
        }
    }
}
