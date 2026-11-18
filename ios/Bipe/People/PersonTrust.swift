// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

// Spec person-profile: the types and trust rules of a person's profile, shared by iOS (PersonProfileModel) and the Mac
// (MacPersonProfile). Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.

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

extension InviteTarget {
    enum Outcome: Equatable { case sent, alreadyMember, refused, limitExceeded, failed }

    /// Action addToGroup, step 1: the user's groups where RoomPowerLevels.canOwnUserInvite, by name.
    static func all(client: Client) async -> [InviteTarget] {
        var targets: [InviteTarget] = []
        for room in client.rooms() where room.membership() == .joined {
            guard let info = try? await room.roomInfo(), !info.isDm, !info.isDirect, !info.isSpace else { continue }
            guard let powerLevels = try? await room.getPowerLevels(), powerLevels.canOwnUserInvite() else { continue }
            targets.append(InviteTarget(roomId: info.id, name: info.displayName ?? ""))
        }
        return targets.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// Action addToGroup, step 2: invites the person to this group.
    func invite(_ userId: String, client: Client) async -> Outcome {
        guard let room = try? client.getRoom(roomId: roomId) else { return .failed }
        if let member = try? await room.member(userId: userId), member.membership == .join || member.membership == .invite {
            return .alreadyMember
        }
        do {
            try await room.inviteUserById(userId: userId)
            return .sent
        } catch let error as ClientError {
            if case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error { return .refused }
            if case .MatrixApi(kind: .limitExceeded(retryAfterMs: _), code: _, msg: _, details: _) = error { return .limitExceeded }
            return .failed
        } catch {
            return .failed
        }
    }
}

/// The trust state of spec person-profile: seals, proofs, facts and marks, verified on this device. Fail closed.
@MainActor
struct PersonTrust {
    let userId: String
    /// The account that looks: its own profile shows no facts.
    let ownUserId: String
    let displayName: String
    let seals: BipeCompanySeals
    /// The SDK's identity of the person. nil while not read, or when the SDK could not read it.
    let identity: BipeTrustSources.Identity?
    /// The identity read has run at least once: a nil identity then means lifecycle unknown.
    let identityRead: Bool
    /// The answer of GET /v1/badges/{userId}/proofs, each badge verified on this device.
    let loadedProofs: [BipePersonProof]

    /// Encryption.userIdentity with fallbackToServer true, as the facts read it.
    static func identity(client: Client, userId: String) async -> BipeTrustSources.Identity? {
        guard let identity = try? await client.encryption().userIdentity(userId: userId, fallbackToServer: true) else { return nil }
        return BipeTrustSources.Identity(masterKey: identity.masterKey(), isVerified: identity.isVerified(),
                                         hasVerificationViolation: identity.hasVerificationViolation())
    }

    /// isCompany of the spec: GET /v1/accounts/{userId}/type through the seal store (ADR 0132).
    var isCompany: Bool { seals.isCompany(userId) }

    /// The seal state from the seal store, verified on the device. Fail closed.
    var seal: BipeCompanySealState { seals.state(for: userId) }

    /// The person seal from the seal store (bipe#596). Fail closed.
    var personSeal: BipePersonSealState { seals.personState(for: userId) }

    /// facts of the spec: identity proof, personal confirmation and changes, apart (ADR 0074, BipeTrustSources).
    var facts: BipeTrustFacts {
        let sealSource: BipeTrustSources.Seal = switch (personSeal, seal) {
        case (.verified(let payload), _), (_, .verified(let payload)): .verified(name: payload.name)
        case (.suspended, _), (_, .suspended): .suspended
        default: .none
        }
        return BipeTrustSources.profileFacts(userId: userId, identity: identity, seal: sealSource)
    }

    /// The identity read ran and gave nothing: bipe_trust_lifecycle_unknown with bipe_trust_lifecycle_retry_action.
    var lifecycleUnknown: Bool {
        identityRead && facts.change == "unknown"
    }

    /// proofs of the spec, strongest first. A failed proofs call falls back on the compact seal. A suspended or a
    /// missing seal gives none (ADR 0275 points 2 and 14).
    var proofLines: [BipePersonProof] {
        guard facts.identity == "verified", let strongest = seals.strongestProof(for: userId) else { return [] }
        return loadedProofs.isEmpty ? [strongest] : loadedProofs
    }

    /// bipe_banner_unverified_title: no proof and no company mark, once the store answered. An official account has
    /// its own badge, and the own profile shows no facts.
    var showsUnverifiedLine: Bool {
        seals.hasAnswer(for: userId) && userId != ownUserId && !isOfficial
            && proofLines.isEmpty && !marks.contains { $0.kind != .confirmedInPerson }
    }

    /// marks of the spec: only marks that verify on this device.
    var marks: [TrustMark] {
        var marks: [TrustMark] = []
        let facts = facts
        if case .verified = personSeal {
            // Only when the badge name differs from the display name: else the header already shows it (spec notes).
            if let name = facts.verifiedName, let strongest = proofLines.first,
               NameNormalizer.normalize(name) != NameNormalizer.normalize(displayName) {
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
        NameNormalizer.reservedCategory(for: displayName) != nil && !isOfficial && !seal.isVerified
    }
}

/// Spec person-profile reach notice (bipe-chat/bipe#846), after M_LIMIT_EXCEEDED on a new DM or an add to a group.
enum ReachNotice {
    /// The budget notice when GET /v1/me/reach says the budget is spent. Else, and when the read fails, the generic error.
    @MainActor
    static func load(api: BipeAPIClient, accessToken: @MainActor () -> String?) async -> String {
        guard let status = try? await BipeAPIClient.withTokenRetry(accessToken: accessToken,
                                                                   { (token: String) async throws(BipeAPIError) -> BipeReachStatus in
            try await api.reach(accessToken: token)
        }), status.isSpent else { return BipeStrings.errorGeneric }
        return text(status)
    }

    /// bipe_reach_limit_body with resets_at as a local date and time, then the step of raise_by.
    static func text(_ status: BipeReachStatus) -> String {
        var text = status.resetsAt.map { BipeStrings.reachLimitBody($0.formatted(date: .abbreviated, time: .shortened)) }
            ?? BipeStrings.reachLimitBodyNone
        switch status.raiseBy {
        case .verify: text += " " + BipeStrings.accountStatusRaiseVerify
        case .wait: text += " " + BipeStrings.accountStatusRaiseWait
        case nil: break
        }
        return text
    }
}
