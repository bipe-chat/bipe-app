// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// The signed `cvm` field of a cnpj badge (bipe-shared docs/badge-attestation.md, ADR 0077). Every field is read
/// leniently: a value of the wrong type is nil, and BipeCvmLines then shows no line.
public struct BipeCvmField: Codable, Equatable, Sendable {
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

/// The CVM lines of the company seal detail (ADR 0077 point 3). The rule runs in bipe-core (`cvmLines`, ADR 0267) and
/// matches bipe-shared tools/lib/cvm.mjs and test-vectors/cvm-lines.json. Fail closed: a missing or bad field shows
/// nothing.
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

    public static func lines(_ field: BipeCvmField?) -> Result {
        // The field goes back to JSON for the core. A value of the wrong type was decoded as nil and stays out.
        guard let field, let data = try? JSONEncoder().encode(field) else { return .none }
        let result = cvmLines(fieldJson: String(decoding: data, as: UTF8.self))
        return Result(lines: result.lines.map { Line(key: Key($0.key), arg: $0.arg) }, danger: result.danger)
    }
}

extension BipeCvmLines.Key {
    init(_ key: CvmLineKey) {
        switch key {
        case .registered: self = .registered
        case .cancelled: self = .cancelled
        case .noRegistration: self = .noRegistration
        case .bankNote: self = .bankNote
        case .stopOrder: self = .stopOrder
        }
    }
}
