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

    /// The proofs of badges that already passed ``BadgeVerifier``, strongest first. Brazilian schemes come first in
    /// their registry order. A person scheme of another country follows, state before bank, named from the issuer
    /// registry (ADR 0272). A scheme with no name anywhere gives no proof (fail closed).
    public static func proofs(_ payloads: [BadgePayload]) -> [BipePersonProof] {
        let local = schemes(payloads.map { (lvl: $0.lvl.rawValue, scheme: $0.scheme?.id) }).compactMap { scheme in
            payloads.first { $0.lvl.rawValue == scheme.alias || $0.scheme?.id == scheme.id }
                .map { BipePersonProof(scheme: scheme, payload: $0) }
        }
        let foreign = payloads.compactMap { payload -> BipePersonProof? in
            guard payload.lvl == .person, let scheme = payload.scheme,
                  !BipeRegistries.personSchemes.contains(where: { $0.id == scheme.id }),
                  let name = BipeSchemeNames.name(scheme.id) else { return nil }
            let mark = scheme.assurance == "state" ? "fill" : "regular"
            return BipePersonProof(scheme: PersonScheme(id: scheme.id, alias: nil, assurance: scheme.assurance, mark: mark,
                                                        namePtBR: name, nameEn: name),
                                   payload: payload)
        }
        return local + foreign.sorted { $0.isFill && !$1.isFill }
    }
}
