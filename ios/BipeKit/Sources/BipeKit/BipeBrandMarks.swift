// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Observation
import SwiftUI

/// One verified brand mark on a reseller's work account (ADR 0162, spec brand-mark).
public struct BipeBrandMark: Sendable, Equatable, Identifiable {
    /// The mark ID.
    public let id: String
    /// The brand's Matrix user ID.
    public let brand: String
    /// The brand's name, as in the brand's own CNPJ badge.
    public let brandName: String
    /// The brand's normalised CNPJ.
    public let cnpj: String
    /// One id of `BipeRegistries.brandMarkLabels`.
    public let label: String
}

/// Fetches, verifies and caches the brand marks of accounts (ADR 0162). One instance per user session.
///
/// Reads are synchronous and observable, like ``BipeCompanySeals``. An answer is kept for
/// `BipeRegistries.brandMarkRecheckMinutes`, so an ended mark goes within that time. A failed fetch shows no mark and no
/// notice (fail closed). The phone keeps the mark IDs it showed per account; a fresh answer without one of them gives
/// the ended notice for that brand, once.
@MainActor @Observable
public final class BipeBrandMarks {
    private struct Entry {
        let marks: [BipeBrandMark]
        let endedBrand: String?
        let expiry: Date
    }
    
    private var entries: [String: Entry] = [:]
    
    @ObservationIgnored private let apiClient: BipeAPIClient
    @ObservationIgnored private let masterKey: @MainActor (String) async throws -> String?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let seenKey: String
    /// User ID to the mark IDs shown on this phone, each with its brand's name.
    @ObservationIgnored private var seen: [String: [String: String]]
    @ObservationIgnored private var inFlight: Set<String> = []
    
    private static let ttl = TimeInterval(BipeRegistries.brandMarkRecheckMinutes * 60)
    private static let errorTTL: TimeInterval = 2 * 60
    
    /// `masterKey` returns the account's cross-signing master key from the Matrix SDK, `nil` when it has none.
    public init(apiClient: BipeAPIClient,
                sessionUserID: String,
                defaults: UserDefaults = .standard,
                masterKey: @escaping @MainActor (String) async throws -> String?) {
        self.apiClient = apiClient
        self.masterKey = masterKey
        self.defaults = defaults
        seenKey = "bipe.brandMarks.seen.\(sessionUserID)"
        seen = (defaults.dictionary(forKey: seenKey) as? [String: [String: String]]) ?? [:]
    }
    
    /// The verified marks, oldest grant first. Queues a fetch when the cache has no fresh value.
    public func marks(for userID: String) -> [BipeBrandMark] {
        entry(for: userID)?.marks ?? []
    }
    
    /// The brand of a mark this phone showed on the account and that is gone now, else `nil`.
    public func endedBrand(for userID: String) -> String? {
        entry(for: userID)?.endedBrand
    }
    
    // MARK: - Private
    
    private func entry(for userID: String) -> Entry? {
        guard BipeServers.isBipeUserID(userID) else { return nil }
        let entry = entries[userID]
        if (entry?.expiry ?? .distantPast) < .now {
            fetch(userID)
        }
        return entry
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
        let envelopes: [Data]
        let key: String?
        do {
            envelopes = try await apiClient.brandMarks(userID: userID)
            key = envelopes.isEmpty ? nil : try await masterKey(userID)
        } catch {
            entries[userID] = Entry(marks: [], endedBrand: nil, expiry: .now.addingTimeInterval(Self.errorTTL))
            return
        }
        
        var marks: [BipeBrandMark] = []
        if let verifier = try? BadgeVerifier.bipeTrusted() {
            for json in envelopes {
                guard case .valid(let payload) = verifier.verify(envelopeJSON: json, sender: userID, senderDevice: "", senderMasterKey: key),
                      payload.lvl == .brandMark, let mark = payload.brandMark, let cnpj = payload.cnpj,
                      !marks.contains(where: { $0.id == mark.mark }) else { continue }
                marks.append(BipeBrandMark(id: mark.mark, brand: mark.brand, brandName: payload.name, cnpj: cnpj, label: mark.label))
            }
        }
        marks = Array(marks.prefix(BipeRegistries.brandMarkMaxPerAccount))
        
        // Only the server's answer ends a mark: a mark that is still listed but does not verify is hidden, not ended.
        let listed = Set(envelopes.compactMap(Self.unverifiedMarkID))
        let shownBefore = seen[userID] ?? [:]
        let ended = shownBefore.filter { !listed.contains($0.key) }
        var stillShown = shownBefore.filter { listed.contains($0.key) }
        for mark in marks {
            stillShown[mark.id] = mark.brandName
        }
        setSeen(userID, stillShown)
        entries[userID] = Entry(marks: marks,
                                endedBrand: ended.values.sorted().first,
                                expiry: .now.addingTimeInterval(Self.ttl))
    }
    
    /// The `mark` field of an envelope's payload, read without trust: it only tells which marks the server still lists.
    private static func unverifiedMarkID(_ json: Data) -> String? {
        guard let envelope = try? JSONDecoder().decode(BadgeEnvelope.self, from: json),
              let bytes = Data(base64URLEncoded: envelope.payload),
              let payload = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { return nil }
        return payload["mark"] as? String
    }
    
    private func setSeen(_ userID: String, _ marks: [String: String]) {
        guard seen[userID] ?? [:] != marks else { return }
        seen[userID] = marks.isEmpty ? nil : marks
        defaults.set(seen, forKey: seenKey)
    }
}

public extension EnvironmentValues {
    /// The session's brand marks. `nil` outside a Bipe session: views show no mark.
    @Entry var bipeBrandMarks: BipeBrandMarks?
}
