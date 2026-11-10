// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Observation
import SwiftUI

/// What the app shows for an account's company seal (bipe#252).
public enum BipeCompanySealState: Equatable, Sendable {
    /// No seal and no notice.
    case none
    /// A verified company: a valid `cnpj` badge bound to the account's master key.
    case verified(BadgePayload)
    /// The account had a seal and the server ended the claim.
    case revoked(BipeBadgeGoneReason)
    /// The account had a seal and its badge no longer verifies, e.g. after a master key reset.
    case suspended
}

/// What the app shows for a person's seal: a `pix` or `govbr` badge (bipe#596, ADR 0239).
public enum BipePersonSealState: Equatable, Sendable {
    /// No seal and no notice.
    case none
    /// A verified name: a valid badge bound to the account's master key.
    case verified(BadgePayload)
    /// The account had a seal and its badge no longer verifies: a master key reset.
    case suspended
}

/// The compact mark next to a name (spec person-profile notes). The same in every place.
public enum BipeSealMark: Equatable, Sendable {
    /// Assurance bank: `sealCheck` regular (ADR 0275).
    case personBank
    /// Assurance state: `sealCheck` fill (ADR 0275).
    case personState
    case company
    case personSuspended
    case companySuspended

    /// What the mark says to a screen reader.
    public var label: String {
        switch self {
        case .personBank: Self.personLabel("bank")
        case .personState: Self.personLabel("state")
        case .company: BipeStrings.badgeCompany
        case .personSuspended: BipeStrings.badgeSuspended
        case .companySuspended: BipeStrings.badgeCompanySuspended
        }
    }

    /// The apps decode only alias levels, and each assurance has one alias scheme (registries/schemes.json).
    private static func personLabel(_ assurance: String) -> String {
        BipeRegistries.personSchemes.first { $0.assurance == assurance }
            .map { BipeStrings.badgeVerifiedVia($0.localizedName) } ?? BipeStrings.badgeVerified
    }

    public var isSuspended: Bool { self == .personSuspended || self == .companySuspended }

    public var tint: Color { isSuspended ? BipeColor.trustCautionInk : BipeColor.trustVerifiedInk }
}

/// Fetches, verifies and caches company and person badges, and which accounts are companies. One instance per user session.
///
/// The account type draws a company avatar as a rounded square, verified or not (ADR 0132). It never changes after
/// sign-up, so known companies stay in `UserDefaults`. An unknown type gives a circle.
///
/// Reads are synchronous and observable, like ``BipeHandleResolver``. A failed fetch never shows a seal (fail closed).
/// The notices need a seal seen before, so the store keeps the IDs with a seal in `UserDefaults`.
@MainActor @Observable
public final class BipeCompanySeals {
    private struct Entry {
        let state: BipeCompanySealState
        let expiry: Date
    }
    
    private var entries: [String: Entry] = [:]
    /// Verified `institution` badges (bipe#245). Set and cleared with the entry of the same account.
    private var institutions: [String: BadgePayload] = [:]
    /// Person seals (bipe#596). Set and cleared with the entry of the same account.
    private var persons: [String: BipePersonSealState] = [:]
    private var companies: Set<String>
    
    @ObservationIgnored private let apiClient: BipeAPIClient
    @ObservationIgnored private let masterKey: @MainActor (String) async throws -> String?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let seenKey: String
    @ObservationIgnored private var seen: Set<String>
    @ObservationIgnored private var inFlight: Set<String> = []
    @ObservationIgnored private let companiesKey: String
    @ObservationIgnored private var typeExpiry: [String: Date] = [:]
    @ObservationIgnored private var typesInFlight: Set<String> = []
    
    private static let ttl: TimeInterval = 10 * 60
    private static let errorTTL: TimeInterval = 2 * 60
    private static let typeTTL: TimeInterval = 24 * 60 * 60
    
    /// `masterKey` returns the account's cross-signing master key from the Matrix SDK, `nil` when the account has none.
    /// It throws when the lookup fails: the store then treats it as a network error, not as a suspended seal.
    public init(apiClient: BipeAPIClient,
                sessionUserID: String,
                defaults: UserDefaults = .standard,
                masterKey: @escaping @MainActor (String) async throws -> String?) {
        self.apiClient = apiClient
        self.masterKey = masterKey
        self.defaults = defaults
        seenKey = "bipe.companySeals.seen.\(sessionUserID)"
        seen = Set(defaults.stringArray(forKey: seenKey) ?? [])
        companiesKey = "bipe.companySeals.companies.\(sessionUserID)"
        companies = Set(defaults.stringArray(forKey: companiesKey) ?? [])
    }
    
    /// The cached state. Queues a fetch when the cache has no fresh value.
    public func state(for userID: String) -> BipeCompanySealState {
        guard BipeServers.isBipeUserID(userID) else { return .none }
        let entry = entries[userID]
        if (entry?.expiry ?? .distantPast) < .now {
            fetch(userID)
        }
        // A badge past its exp shows no seal while the new fetch runs.
        if case .verified(let payload) = entry?.state, Date(timeIntervalSince1970: TimeInterval(payload.exp)) <= .now {
            return .none
        }
        return entry?.state ?? .none
    }
    
    /// The verified badge, or `nil`.
    public func badge(for userID: String) -> BadgePayload? {
        if case .verified(let payload) = state(for: userID) {
            return payload
        }
        return nil
    }
    
    /// The verified institution badge, or `nil` (bipe#245): its messages show as official notice cards.
    /// Queues a fetch like ``state(for:)``. Fail closed: no fresh answer, no notice.
    public func institution(for userID: String) -> BadgePayload? {
        _ = state(for: userID)
        guard let payload = institutions[userID], Date(timeIntervalSince1970: TimeInterval(payload.exp)) > .now else {
            return nil
        }
        return payload
    }
    
    /// The person seal, queued like ``state(for:)``. A badge past its exp shows no seal while the new fetch runs.
    public func personState(for userID: String) -> BipePersonSealState {
        _ = state(for: userID)
        let state: BipePersonSealState = persons[userID] ?? .none
        if case .verified(let payload) = state, Date(timeIntervalSince1970: TimeInterval(payload.exp)) <= .now {
            return .none
        }
        return state
    }
    
    /// The verified badge of a company or a person, or `nil`.
    public func verifiedBadge(for userID: String) -> BadgePayload? {
        if case .verified(let payload) = personState(for: userID) {
            return payload
        }
        return badge(for: userID)
    }
    
    /// The compact mark next to a name, or `nil`. A revoked company seal has no mark.
    public func mark(for userID: String) -> BipeSealMark? {
        switch personState(for: userID) {
        case .verified(let payload):
            // The strongest proof only (ADR 0275 point 15). The server answers the strongest; the mark comes from the badge.
            guard let proof = BipePersonProofs.proofs([payload]).first else { return nil }
            return proof.isFill ? .personState : .personBank
        case .suspended: return .personSuspended
        case .none: break
        }
        switch state(for: userID) {
        case .verified: return .company
        case .suspended: return .companySuspended
        case .none, .revoked: return nil
        }
    }
    
    /// The strongest proof that verified on this device, or `nil`.
    public func strongestProof(for userID: String) -> BipePersonProof? {
        guard case .verified(let payload) = personState(for: userID) else { return nil }
        return BipePersonProofs.proofs([payload]).first
    }

    /// The store has an answer for the account, so "no seal" is a fact and not a fetch that still runs.
    public func hasAnswer(for userID: String) -> Bool {
        _ = state(for: userID)
        return entries[userID] != nil
    }

    /// Every person proof of the account that verifies on this device, strongest first (ADR 0275).
    /// A failed call, no master key or a badge that fails gives no proof for it (fail closed).
    public func proofs(for userID: String) async -> [BipePersonProof] {
        guard BipeServers.isBipeUserID(userID),
              let envelopes = try? await apiClient.badgeProofs(userID: userID),
              let key = try? await masterKey(userID),
              let verifier = try? BadgeVerifier.bipeTrusted() else { return [] }
        let payloads = envelopes.compactMap { json -> BadgePayload? in
            if case .valid(let payload) = verifier.verify(envelopeJSON: json, sender: userID, senderMasterKey: key) { payload } else { nil }
        }
        return BipePersonProofs.proofs(payloads)
    }

    /// Whether the account is a company (ADR 0132). Queues a fetch when the cache has no fresh value.
    public func isCompany(_ userID: String) -> Bool {
        guard BipeServers.isBipeUserID(userID) else { return false }
        if (typeExpiry[userID] ?? .distantPast) < .now {
            fetchType(userID)
        }
        return companies.contains(userID)
    }
    
    // MARK: - Private
    
    private func fetchType(_ userID: String) {
        guard !typesInFlight.contains(userID) else { return }
        typesInFlight.insert(userID)
        Task { [weak self] in
            await self?.loadType(userID)
        }
    }
    
    private func loadType(_ userID: String) async {
        defer { typesInFlight.remove(userID) }
        do {
            let type = try await apiClient.accountType(userID: userID)
            typeExpiry[userID] = .now.addingTimeInterval(Self.typeTTL)
            setCompany(userID, type == .company)
        } catch {
            typeExpiry[userID] = .now.addingTimeInterval(Self.errorTTL)
        }
    }
    
    private func setCompany(_ userID: String, _ isCompany: Bool) {
        let changed = isCompany ? companies.insert(userID).inserted : companies.remove(userID) != nil
        if changed {
            defaults.set(companies.sorted(), forKey: companiesKey)
        }
    }
    
    private func fetch(_ userID: String) {
        guard !inFlight.contains(userID) else { return }
        inFlight.insert(userID)
        Task { [weak self] in
            await self?.load(userID)
        }
    }
    
    private func load(_ userID: String) async {
        defer { inFlight.remove(userID) }
        let wasSeen = seen.contains(userID)
        let wasPersonSeen = seen.contains(Self.personSeenKey(userID))
        
        let lookup: BipeBadgeLookup
        do {
            lookup = try await apiClient.badge(userID: userID)
        } catch {
            storeFailure(for: userID)
            return
        }
        
        switch lookup {
        case .none:
            setSeen(userID, false)
            setSeen(Self.personSeenKey(userID), false)
            store(.none, for: userID)
        case .gone(let reason):
            setSeen(Self.personSeenKey(userID), false)
            store(wasSeen ? .revoked(reason) : .none, for: userID)
        case .envelope(let json):
            let key: String?
            do {
                key = try await masterKey(userID)
            } catch {
                storeFailure(for: userID)
                return
            }
            let verifier: BadgeVerifier
            do {
                verifier = try BadgeVerifier.bipeTrusted()
            } catch {
                storeInvalid(for: userID, wasSeen: wasSeen, wasPersonSeen: wasPersonSeen)
                return
            }
            switch verifier.verify(envelopeJSON: json, sender: userID, senderMasterKey: key) {
            case .valid(let payload) where payload.lvl.badgeClass == .org:
                setSeen(userID, true)
                let expiry = min(Date(timeIntervalSince1970: TimeInterval(payload.exp)), .now.addingTimeInterval(Self.ttl))
                entries[userID] = Entry(state: .verified(payload), expiry: expiry)
                institutions[userID] = nil
                persons[userID] = nil
            case .valid(let payload) where payload.lvl == .institution:
                store(.none, for: userID)
                institutions[userID] = payload
            case .valid(let payload) where payload.lvl.badgeClass == .person:
                // The seal shows only when identityVerified holds (registries/trust-facts.json).
                let identity = BipeTrustSources.Identity(masterKey: key, isVerified: false, hasVerificationViolation: false)
                let facts = BipeTrustSources.profileFacts(userId: userID, identity: identity, seal: .verified(name: payload.name))
                guard facts.identity == "verified" else {
                    store(.none, for: userID)
                    return
                }
                setSeen(Self.personSeenKey(userID), true)
                let expiry = min(Date(timeIntervalSince1970: TimeInterval(payload.exp)), .now.addingTimeInterval(Self.ttl))
                store(.none, for: userID)
                entries[userID] = Entry(state: .none, expiry: expiry)
                persons[userID] = .verified(payload)
            case .valid:
                store(.none, for: userID)
            case .invalid:
                storeInvalid(for: userID, wasSeen: wasSeen, wasPersonSeen: wasPersonSeen)
            }
        }
    }
    
    /// Never keeps a seal without a fresh answer. A notice stays.
    private func storeFailure(for userID: String) {
        let old = entries[userID]?.state ?? .none
        let state: BipeCompanySealState = if case .verified = old {
            .none
        } else {
            old
        }
        entries[userID] = Entry(state: state, expiry: .now.addingTimeInterval(Self.errorTTL))
        institutions[userID] = nil
        if case .verified = persons[userID] {
            persons[userID] = nil
        }
    }
    
    /// A badge that does not verify suspends only a seal this phone saw before.
    private func storeInvalid(for userID: String, wasSeen: Bool, wasPersonSeen: Bool) {
        store(wasSeen ? .suspended : .none, for: userID)
        if wasPersonSeen {
            persons[userID] = .suspended
        }
    }
    
    private func store(_ state: BipeCompanySealState, for userID: String) {
        entries[userID] = Entry(state: state, expiry: .now.addingTimeInterval(Self.ttl))
        institutions[userID] = nil
        persons[userID] = nil
    }
    
    /// A person seal seen before shares the persisted set with company seals. A user ID starts with `@`, so the keys differ.
    private static func personSeenKey(_ userID: String) -> String {
        "person:" + userID
    }
    
    private func setSeen(_ userID: String, _ isSeen: Bool) {
        let changed = isSeen ? seen.insert(userID).inserted : seen.remove(userID) != nil
        if changed {
            defaults.set(Array(seen), forKey: seenKey)
        }
    }
}

public extension EnvironmentValues {
    /// The session's company seals. `nil` outside a Bipe session: views show no seal.
    @Entry var bipeCompanySeals: BipeCompanySeals?
}

/// Display helpers for a normalised CNPJ (`test-vectors/cnpj.json`).
public enum BipeCNPJ {
    /// `12.345.678/0001-90`. The alphanumeric CNPJ uses the same groups. Other input comes back unchanged.
    public static func formatted(_ cnpj: String) -> String {
        let characters = Array(cnpj)
        guard characters.count == 14 else { return cnpj }
        let part = { (range: Range<Int>) in String(characters[range]) }
        return "\(part(0..<2)).\(part(2..<5)).\(part(5..<8))/\(part(8..<12))-\(part(12..<14))"
    }
    
    /// The middle of a `YYYY-MM` registry release month in the local calendar, so any time zone shows the same month.
    public static func checkedDate(_ month: String) -> Date? {
        let parts = month.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return Calendar(identifier: .gregorian).date(from: DateComponents(year: parts[0], month: parts[1], day: 15))
    }
}
