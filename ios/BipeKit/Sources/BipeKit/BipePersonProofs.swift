// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// One valid person proof of an account (ADR 0275): a badge that verified on this device, with its scheme.
public struct BipePersonProof: Equatable, Sendable, Identifiable {
    public let scheme: PersonScheme
    public let payload: BadgePayload

    public var id: String { scheme.id }
    /// `sealCheck` fill for assurance state, `sealCheck` regular for assurance bank.
    public var isFill: Bool { scheme.mark == "fill" }
    public var schemeName: String { scheme.localizedName }
    /// The label and the profile line: "Nome verificado via gov.br".
    public var line: String { BipeStrings.badgeVerifiedVia(schemeName) }
}

public extension PersonScheme {
    /// The name of the scheme in the app's language (registries/schemes.json).
    var localizedName: String {
        Bundle.module.preferredLocalizations.first?.hasPrefix("en") == true ? nameEn : namePtBR
    }
}

/// Port of bipe-shared `personProofs` (tools/lib/registries.mjs); test-vectors/person-proofs.json must pass.
public enum BipePersonProofs {
    /// The person schemes of these badges, strongest first, one per scheme. A level or a scheme outside
    /// `BipeRegistries.personSchemes` gives nothing (fail closed).
    public static func schemes(_ badges: [(lvl: String, scheme: String?)]) -> [PersonScheme] {
        let found = badges.compactMap { badge -> String? in
            let schemes = BipeRegistries.personSchemes
            return (badge.lvl == "person"
                ? schemes.first { $0.id == badge.scheme }
                : schemes.first { $0.alias == badge.lvl })?.id
        }
        return BipeRegistries.personSchemes.filter { found.contains($0.id) }
    }

    /// The proofs of badges that already passed ``BadgeVerifier``, strongest first.
    public static func proofs(_ payloads: [BadgePayload]) -> [BipePersonProof] {
        schemes(payloads.map { (lvl: $0.lvl.rawValue, scheme: nil) }).compactMap { scheme in
            payloads.first { $0.lvl.rawValue == scheme.alias }.map { BipePersonProof(scheme: scheme, payload: $0) }
        }
    }
}
