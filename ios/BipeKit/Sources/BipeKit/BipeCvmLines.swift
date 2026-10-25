// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The signed `cvm` field of a cnpj badge (bipe-shared docs/badge-attestation.md, ADR 0077). Every field is read
/// leniently: a value of the wrong type is nil, and BipeCvmLines then shows no line.
public struct BipeCvmField: Decodable, Equatable, Sendable {
    public let st: String?
    public let cat: [String]?
    public let on: String?

    private enum CodingKeys: String, CodingKey { case st, cat, on }

    public init(st: String?, cat: [String]? = nil, on: String? = nil) {
        self.st = st
        self.cat = cat
        self.on = on
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        st = (try? container.decodeIfPresent(String.self, forKey: .st)) ?? nil
        cat = (try? container.decodeIfPresent([String].self, forKey: .cat)) ?? nil
        on = (try? container.decodeIfPresent(String.self, forKey: .on)) ?? nil
    }
}

/// The CVM lines of the company seal detail (ADR 0077 point 3). Same rule as bipe-shared tools/lib/cvm.mjs and
/// test-vectors/cvm-lines.json. Fail closed: a missing or bad field shows nothing.
public enum BipeCvmLines {
    public enum Key: String, Sendable, Equatable {
        case registered = "bipe_cvm_registered"
        case cancelled = "bipe_cvm_cancelled"
        case noRegistration = "bipe_cvm_no_registration"
        case bankNote = "bipe_cvm_bank_note"
        case stopOrder = "bipe_cvm_stop_order"
    }

    public struct Line: Sendable, Equatable {
        public let key: Key
        public let arg: String?
    }

    public struct Result: Sendable, Equatable {
        public let lines: [Line]
        /// A stop order: the line is red and chats from the account show bipe_cvm_stop_order_card.
        public let danger: Bool

        static let none = Result(lines: [], danger: false)
    }

    /// YYYY-MM-DD to DD/MM/AAAA, else nil.
    static func displayDate(_ value: String?) -> String? {
        guard let value, let m = value.wholeMatch(of: /(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])/) else { return nil }
        return "\(m.3)/\(m.2)/\(m.1)"
    }

    public static func lines(_ field: BipeCvmField?) -> Result {
        guard let field else { return .none }
        switch field.st {
        case "stop_order":
            guard let on = displayDate(field.on) else { return .none }
            return Result(lines: [Line(key: .stopOrder, arg: on)], danger: true)
        case "registered":
            guard let cat = field.cat, !cat.isEmpty, cat.count <= 10,
                  cat.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.count <= 120 }) else { return .none }
            var seen: [String] = []
            for c in cat.map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) }) where !seen.contains(c) { seen.append(c) }
            return Result(lines: seen.map { Line(key: .registered, arg: $0) }, danger: false)
        case "cancelled":
            guard let on = displayDate(field.on) else { return .none }
            return Result(lines: [Line(key: .cancelled, arg: on)], danger: false)
        case "no_registration":
            return Result(lines: [Line(key: .noRegistration, arg: nil), Line(key: .bankNote, arg: nil)], danger: false)
        default:
            return .none
        }
    }
}
