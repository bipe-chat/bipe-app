// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

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
        } catch PeopleError.limitExceeded {
            notice = await people.reachNotice()
        } catch {
            notice = BipeStrings.errorGeneric
        }
        return nil
    }

    /// The trust logic of the spec, shared with the Mac (PersonTrust).
    private var trust: PersonTrust {
        PersonTrust(userId: userId, ownUserId: people.session.userId, displayName: person.displayName, seals: people.seals,
                    identity: trustIdentity, identityRead: identityRead, loadedProofs: loadedProofs)
    }

    /// isCompany of the spec: GET /v1/accounts/{userId}/type through the seal store (ADR 0132).
    var isCompany: Bool { trust.isCompany }

    /// The seal state from the seal store, verified on the device. Fail closed.
    var seal: BipeCompanySealState { trust.seal }

    /// The person seal from the seal store (bipe#596). Fail closed.
    var personSeal: BipePersonSealState { trust.personSeal }

    /// Action retryFacts (and part of appear): reads the person's identity again.
    func readIdentity() async {
        trustIdentity = await people.trustIdentity(userId)
        identityRead = true
    }

    /// facts of the spec: identity proof, personal confirmation and changes, apart (ADR 0074, BipeTrustSources).
    var facts: BipeTrustFacts { trust.facts }

    /// The identity read ran and gave nothing: bipe_trust_lifecycle_unknown with bipe_trust_lifecycle_retry_action.
    var lifecycleUnknown: Bool { trust.lifecycleUnknown }

    /// proofs of the spec, strongest first.
    var proofLines: [BipePersonProof] { trust.proofLines }

    /// bipe_banner_unverified_title: no proof and no company mark, once the store answered.
    var showsUnverifiedLine: Bool { trust.showsUnverifiedLine }

    /// marks of the spec: only marks that verify on this device.
    var marks: [TrustMark] { trust.marks }

    /// isOfficial of the spec: the exact user ID is an official Bipe account (ADR 0117).
    var isOfficial: Bool { trust.isOfficial }

    /// reservedNameWarning of the spec.
    var reservedNameWarning: Bool { trust.reservedNameWarning }

    /// The profile link (action share), only when the person has an @.
    var shareLink: URL? {
        person.handle.flatMap(BipeProfileLink.url(handle:))
    }

    /// Action addToGroup, step 1: the user's groups where RoomPowerLevels.canOwnUserInvite.
    func inviteTargets() async -> [InviteTarget] {
        await InviteTarget.all(client: people.session.client)
    }

    /// Action addToGroup, step 2: invites the person to the chosen group.
    func invite(to target: InviteTarget) async {
        switch await target.invite(userId, client: people.session.client) {
        case .sent: notice = BipeStrings.dmInviteToGroupSent
        case .alreadyMember: notice = BipeStrings.dmInviteToGroupAlreadyMember
        case .refused: notice = BipeStrings.appGroupInviteRefused(person.displayName)
        case .limitExceeded: notice = await people.reachNotice()
        case .failed: notice = BipeStrings.errorGeneric
        }
    }
}
