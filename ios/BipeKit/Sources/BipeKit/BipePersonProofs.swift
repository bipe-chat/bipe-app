// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
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

/// Scheme names (ADR 0272). A Brazilian scheme has its name in the app build. Another country's scheme takes its name
/// from the newest valid issuer registry, so a new country needs no app release. `nil`: the scheme is unknown.
public enum BipeSchemeNames {
    public static func name(_ id: String, registryJSON: String? = SignedLists.shared.current(.issuers)?.payloadJson) -> String? {
        if let local = BipeRegistries.personSchemes.first(where: { $0.id == id }) { return local.localizedName }
        guard let data = registryJSON?.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let schemes = root["schemes"] as? [[String: Any]],
              let scheme = schemes.first(where: { $0["id"] as? String == id }),
              let names = scheme["names"] as? [String: String] else { return nil }
        let english = Bundle.module.preferredLocalizations.first?.hasPrefix("en") == true
        return (english ? names["en"] : names["pt-BR"]) ?? names["pt-BR"] ?? names["en"]
    }
}

/// `personProofs` of bipe-shared, run in bipe-core (`personProofs`, ADR 0267, ADR 0275).
/// test-vectors/person-proofs.json must pass.
public enum BipePersonProofs {
    /// The person schemes of these badges: Brazilian first, then other countries', each strongest first, one per
    /// scheme. A level or a scheme outside the issuer registry, or a scheme with no name, gives nothing (fail closed).
    public static func schemes(_ badges: [(lvl: String, scheme: String?)],
                               issuerRegistryJSON: String? = SignedLists.shared.current(.issuers)?.payloadJson) -> [PersonScheme] {
        let known = badges.compactMap { badge in BadgeLevel(rawValue: badge.lvl).map { PersonBadge(level: $0.core, scheme: badge.scheme) } }
        return personProofs(badges: known, issuerRegistryJson: issuerRegistryJSON ?? "").compactMap { scheme(of: $0, registryJSON: issuerRegistryJSON) }
    }

    /// The proofs of badges that already passed ``BadgeVerifier``, in the order of ``schemes(_:issuerRegistryJSON:)``.
    /// A foreign scheme takes its name from the issuer registry (ADR 0272).
    public static func proofs(_ payloads: [BadgePayload],
                              issuerRegistryJSON: String? = SignedLists.shared.current(.issuers)?.payloadJson) -> [BipePersonProof] {
        let badges = payloads.map { PersonBadge(level: $0.lvl.core, scheme: $0.scheme?.id) }
        return personProofs(badges: badges, issuerRegistryJson: issuerRegistryJSON ?? "").compactMap { proof in
            let index = Int(proof.badgeIndex)
            guard payloads.indices.contains(index), let scheme = scheme(of: proof, registryJSON: issuerRegistryJSON) else { return nil }
            return BipePersonProof(scheme: scheme, payload: payloads[index])
        }
    }

    /// The scheme of a core proof: the build's entry for a Brazilian scheme, else one named from the registry.
    private static func scheme(of proof: PersonProof, registryJSON: String?) -> PersonScheme? {
        if let local = BipeRegistries.personSchemes.first(where: { $0.id == proof.scheme }) { return local }
        guard let name = BipeSchemeNames.name(proof.scheme, registryJSON: registryJSON) else { return nil }
        let mark = proof.mark == .fill ? "fill" : "regular"
        return PersonScheme(id: proof.scheme, alias: nil, assurance: proof.assurance, mark: mark, namePtBR: name, nameEn: name)
    }
}

nonisolated extension BadgeLevel {
    /// The bipe-core level of the same name.
    var core: BipeCore.BadgeLevel {
        switch self {
        case .pix: .pix
        case .govbr: .govbr
        case .institution: .institution
        case .cnpj: .cnpj
        case .brandMark: .brandMark
        case .person: .person
        case .org: .org
        }
    }
}
