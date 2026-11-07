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

/// Type SocialLink of spec person-profile (ADR 0228).
struct SocialLink: Identifiable, Equatable {
    var platform: SocialPlatform
    /// PublicProfile.socials[platform], without @.
    var handle: String
    /// socialUrl, built on the device. The server sends no URL.
    var url: URL

    var id: String { platform.id }

    /// The links of PublicProfile.socials in registry order. Unknown ids and handles that make no URL are skipped.
    static func links(_ socials: [String: String]?) -> [SocialLink] {
        guard let socials else { return [] }
        return BipeRegistries.profileSocials.compactMap { platform in
            guard let raw = socials[platform.id] else { return nil }
            let handle = BipeSocialHandles.clean(raw)
            guard !handle.isEmpty, let url = URL(string: BipeSocialHandles.url(platform, input: handle)) else { return nil }
            return SocialLink(platform: platform, handle: handle, url: url)
        }
    }
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
    /// The answer of GET /v1/badges/{userId}/proofs, each badge verified on this device.
    @Published private var loadedProofs: [BipePersonProof] = []
    @Published private(set) var person: Person
    /// The SDK's identity of the person (spec facts). nil while not read, or when the SDK could not read it.
    @Published private(set) var trustIdentity: BipeTrustSources.Identity?
    /// The identity read has run at least once: a nil trustIdentity then means lifecycle unknown.
    @Published private(set) var identityRead = false
    @Published private(set) var bio: String?
    /// The public profile photo, for the avatar when the server hides the Matrix profile.
    @Published private(set) var publicPhotoUrl: URL?
    /// Spec socials: empty shows no section.
    @Published private(set) var socials: [SocialLink] = []
    @Published private(set) var dmRoomId: String?
    /// Spec savedNumbers (ADR 0191): the numbers the user saved for this person in the own address book. Empty: no row.
    @Published private(set) var savedNumbers: [String] = []
    /// A one-line notice after an action (invite sent, refused, already a member).
    @Published var notice: String?

    init(people: PeopleService, userId: String, fromDm: Bool) {
        self.people = people
        self.userId = userId
        self.fromDm = fromDm
        person = Person(userId: userId, displayName: nil, avatarUrl: nil)
    }

    /// The user id is in Client.ignoredUsers.
    var isBlocked: Bool {
        people.isBlocked(userId)
    }

    /// Action appear: shows what it has and fills in the rest.
    func appear() async {
        phase = .loading
        dmRoomId = await people.dmRoomId(with: userId)
        do {
            person = try await people.person(userId)
        } catch {
            phase = .failed
            return
        }
        phase = .loaded

        await readIdentity()
        loadedProofs = await people.seals.proofs(for: userId)
        if let handle = person.handle, let profile = try? await people.api.publicProfile(handle: handle) {
            let text = profile.bio?.trimmingCharacters(in: .whitespacesAndNewlines)
            bio = text?.isEmpty == false ? text : nil
            publicPhotoUrl = profile.photoUrl
            socials = SocialLink.links(profile.socials)
        }
        // Spec savedNumbers: may run one address-book lookup first, so it comes last.
        savedNumbers = await people.savedNumbers(of: userId)
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

    /// isCompany of the spec: GET /v1/accounts/{userId}/type through the seal store (ADR 0132).
    var isCompany: Bool {
        people.seals.isCompany(userId)
    }

    /// The seal state from the seal store, verified on the device. Fail closed.
    var seal: BipeCompanySealState {
        people.seals.state(for: userId)
    }

    /// The person seal from the seal store (bipe#596). Fail closed.
    var personSeal: BipePersonSealState {
        people.seals.personState(for: userId)
    }

    /// Action retryFacts (and part of appear): reads the person's identity again.
    func readIdentity() async {
        trustIdentity = await people.trustIdentity(userId)
        identityRead = true
    }

    /// facts of the spec: identity proof, personal confirmation and changes, apart (ADR 0074, BipeTrustSources).
    var facts: BipeTrustFacts {
        let sealSource: BipeTrustSources.Seal = switch (personSeal, seal) {
        case (.verified(let payload), _), (_, .verified(let payload)): .verified(name: payload.name)
        case (.suspended, _), (_, .suspended): .suspended
        default: .none
        }
        return BipeTrustSources.profileFacts(userId: userId, identity: trustIdentity, seal: sealSource)
    }

    /// The identity read ran and gave nothing: bipe_trust_lifecycle_unknown with bipe_trust_lifecycle_retry_action.
    var lifecycleUnknown: Bool {
        identityRead && facts.change == "unknown"
    }

    /// proofs of the spec, strongest first. A failed proofs call falls back on the compact seal. A suspended or a
    /// missing seal gives none (ADR 0275 points 2 and 14).
    var proofLines: [BipePersonProof] {
        guard facts.identity == "verified", let strongest = people.seals.strongestProof(for: userId) else { return [] }
        return loadedProofs.isEmpty ? [strongest] : loadedProofs
    }

    /// bipe_banner_unverified_title: no proof and no company mark, once the store answered. An official account has
    /// its own badge, and the own profile shows no facts.
    var showsUnverifiedLine: Bool {
        people.seals.hasAnswer(for: userId) && userId != people.session.userId && !isOfficial
            && proofLines.isEmpty && !marks.contains { $0.kind != .confirmedInPerson }
    }

    /// marks of the spec: only marks that verify on this device.
    var marks: [TrustMark] {
        var marks: [TrustMark] = []
        let facts = facts
        if case .verified = personSeal {
            // Only when the badge name differs from the display name: else the header already shows it (spec notes).
            if let name = facts.verifiedName, let strongest = proofLines.first,
               NameNormalizer.normalize(name) != NameNormalizer.normalize(person.displayName) {
                marks.append(TrustMark(kind: .verifiedName, text: BipeStrings.badgeVerifiedNameVia(strongest.schemeName, name)))
            }
        } else if case .suspended = personSeal {
            marks.append(TrustMark(kind: .nameSuspended, text: BipeStrings.badgeSuspended))
        } else if case .revoked = seal {
            marks.append(TrustMark(kind: .companyRevoked, text: seal.notice ?? BipeStrings.badgeCompanyRevoked))
        } else if facts.identity == "verified" {
            marks.append(TrustMark(kind: .company, text: BipeStrings.badgeCompany))
        } else if facts.identity == "suspended" || seal == .suspended {
            marks.append(TrustMark(kind: .companySuspended, text: BipeStrings.badgeCompanySuspended))
        }
        if facts.contact == "confirmed" {
            marks.append(TrustMark(kind: .confirmedInPerson, text: BipeStrings.badgeVerifiedContact))
        }
        return marks
    }

    /// isOfficial of the spec: the exact user ID is an official Bipe account (ADR 0117).
    var isOfficial: Bool {
        BipeOfficialAccounts.isOfficial(userId)
    }

    /// reservedNameWarning of the spec. An official account, or a company whose verified seal names it, does not get the warning.
    var reservedNameWarning: Bool {
        NameNormalizer.reservedCategory(for: person.displayName) != nil && !isOfficial && !seal.isVerified
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
