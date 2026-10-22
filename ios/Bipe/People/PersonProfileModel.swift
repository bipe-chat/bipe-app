// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type TrustMark of spec person-profile.
struct TrustMark: Identifiable, Equatable {
    enum Kind: Equatable {
        case verifiedName
        case company
        case companyRevoked
        case companySuspended
        case confirmedInPerson
        case nameSuspended
    }

    var kind: Kind
    var text: String

    var id: String { "\(kind)" }
}

/// A group where the user may invite (action addToGroup).
struct InviteTarget: Identifiable, Equatable {
    var roomId: String
    var name: String

    var id: String { roomId }
}

/// Spec person-profile (bipe-shared product/screens/person-profile.json).
@MainActor
final class PersonProfileModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed
    }

    let userId: String
    let fromDm: Bool
    let people: PeopleService

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var person: Person
    @Published private(set) var isCompany = false
    @Published private(set) var marks: [TrustMark] = []
    @Published private(set) var seal: BipeCompanySealState = .noSeal
    @Published private(set) var reservedNameWarning = false
    @Published private(set) var bio: String?
    @Published private(set) var dmRoomId: String?
    /// A one-line notice after an action (invite sent, refused, already a member).
    @Published var notice: String?

    init(people: PeopleService, userId: String, fromDm: Bool) {
        self.people = people
        self.userId = userId
        self.fromDm = fromDm
        person = Person(userId: userId, displayName: Person.localpart(of: userId))
    }

    /// The user id is in Client.ignoredUsers.
    var isBlocked: Bool {
        people.isBlocked(userId)
    }

    /// Action appear: shows what it has and fills in the rest.
    func appear() async {
        phase = .loading
        dmRoomId = people.dmRoomId(with: userId)
        do {
            person = try await people.person(userId)
        } catch {
            phase = .failed
            return
        }
        phase = .loaded

        isCompany = await people.accountType(userId) == .company
        var newMarks: [TrustMark] = []
        if isCompany {
            seal = await people.sealState(userId)
            switch seal {
            case .verified: newMarks.append(TrustMark(kind: .company, text: BipeStrings.badgeCompany))
            case .revoked: newMarks.append(TrustMark(kind: .companyRevoked, text: seal.notice ?? BipeStrings.badgeCompanyRevoked))
            case .suspended: newMarks.append(TrustMark(kind: .companySuspended, text: BipeStrings.badgeCompanySuspended))
            case .noSeal: break
            }
        }
        if await people.isConfirmedInPerson(userId) {
            newMarks.append(TrustMark(kind: .confirmedInPerson, text: BipeStrings.badgeVerifiedContact))
        }
        marks = newMarks
        // A company whose verified seal names it does not get the warning.
        reservedNameWarning = NameNormalizer.reservedCategory(person.displayName) != nil && !seal.isVerified

        if let handle = person.handle, let profile = try? await people.api.publicProfile(handle: handle) {
            let text = profile.bio?.trimmingCharacters(in: .whitespacesAndNewlines)
            bio = text?.isEmpty == false ? text : nil
        }
    }

    /// Action message: the DM, created when missing. Returns the room to open.
    func message() async -> String? {
        do {
            let roomId = try await people.openOrCreateDM(with: userId)
            dmRoomId = roomId
            return roomId
        } catch PeopleError.inviteRefused {
            notice = BipeStrings.appGroupInviteRefused(person.displayName)
        } catch {
            notice = BipeStrings.errorGeneric
        }
        return nil
    }

    /// The profile link (action share), only when the person has an @.
    var shareLink: URL? {
        person.handle.flatMap(BipeProfileLink.url(handle:))
    }

    /// Action addToGroup, step 1: the user's groups where RoomPowerLevels.canOwnUserInvite.
    func inviteTargets() async -> [InviteTarget] {
        var targets: [InviteTarget] = []
        for room in people.session.client.rooms() where room.membership() == .joined {
            guard let info = try? await room.roomInfo(), !info.isDm, !info.isDirect, !info.isSpace else { continue }
            guard let powerLevels = try? await room.getPowerLevels(), powerLevels.canOwnUserInvite() else { continue }
            targets.append(InviteTarget(roomId: info.id, name: info.displayName ?? ""))
        }
        return targets.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// Action addToGroup, step 2: invites the person to the chosen group.
    func invite(to target: InviteTarget) async {
        guard let room = try? people.session.client.getRoom(roomId: target.roomId) else {
            notice = BipeStrings.errorGeneric
            return
        }
        if let member = try? await room.member(userId: userId), member.membership == .join || member.membership == .invite {
            notice = BipeStrings.dmInviteToGroupAlreadyMember
            return
        }
        do {
            try await room.inviteUserById(userId: userId)
            notice = BipeStrings.dmInviteToGroupSent
        } catch let error as ClientError {
            if case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error {
                notice = BipeStrings.appGroupInviteRefused(person.displayName)
            } else {
                notice = BipeStrings.errorGeneric
            }
        } catch {
            notice = BipeStrings.errorGeneric
        }
    }
}
