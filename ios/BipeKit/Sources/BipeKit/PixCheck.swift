// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import CoreImage
import Foundation

/// "Conferir no Bipe" phase 1 (bipe ADR 0091): the Pix code and the Pix keys in text that the user
/// shared from another app. Runs on the device only. Must match bipe-shared/test-vectors/pix-check.json.
/// The BR Code, its CRC, the keys in the text, the key type and the warning come from bipe-core (`findPixCode`,
/// `pixKeys`, `pixKeyType`, `scamWarningKind`, ADR 0267).
public nonisolated struct PixCheck: Sendable, Equatable {
    public enum Status: String, Sendable {
        case valid, badCrc, malformed
    }
    
    public enum KeyType: String, Sendable {
        case cpf, cnpj, phone, email, random, unknown
    }
    
    /// A BR Code. Every field except `status` is nil or false when the code is malformed.
    public struct Code: Sendable, Equatable {
        public var status: Status
        public var dynamic = false
        public var singleUse = false
        public var key: String?
        public var keyType: KeyType?
        /// The payload location of a dynamic code, without a scheme.
        public var url: String?
        public var name: String?
        public var city: String?
        /// As written in the code, for example "50.00".
        public var amount: String?
        public var txid: String?
        public var info: String?
        
        /// The host part of `url`.
        public var urlHost: String? {
            url.flatMap { $0.split(separator: "/", maxSplits: 1).first.map(String.init) }
        }
    }
    
    /// A Pix key in the text. `types` lists every key pattern that matched the same span, in registry order.
    public struct Key: Sendable, Equatable {
        public let value: String
        public let types: [KeyType]
    }
    
    public let code: Code?
    public let keys: [Key]
    /// The rule 9 warning of the text around the code, never `pixCode` or `pixKey`.
    public let warning: ScamWarningKind?
    
    public var foundPix: Bool {
        code != nil || !keys.isEmpty
    }
    
    /// The screen itself explains Pix, so these warnings are not repeated.
    static let skippedWarnings: Set<ScamWarningKind> = [.pixCode, .pixKey]
    
    public static func check(_ text: String) -> PixCheck {
        let found = findPixCode(text: text)
        let rest = found.map { found in
            let scalars = Array(text.unicodeScalars)
            let before = String(String.UnicodeScalarView(scalars[..<Int(found.start)]))
            let after = String(String.UnicodeScalarView(scalars[Int(found.end)...]))
            return before + " " + after
        } ?? text
        return PixCheck(code: found.map { Code($0.code) },
                        keys: pixKeys(text: text).map { Key(value: $0.value, types: $0.types.compactMap(KeyType.init(rawValue:))) },
                        warning: ScamDetector.warningKind(in: rest, skipping: skippedWarnings))
    }
    
    /// Text of every QR code in an image. Decoding runs on the device.
    public static func qrPayloads(in image: CIImage) -> [String] {
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        return (detector?.features(in: image) ?? []).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
    
    public static func qrPayloads(inImageAt url: URL) -> [String] {
        CIImage(contentsOf: url).map(qrPayloads(in:)) ?? []
    }
    
    // MARK: - Key types
    
    public static func keyType(of key: String) -> KeyType {
        KeyType(pixKeyType(key: key))
    }
}

// MARK: - bipe-core types

extension PixCheck.Code {
    init(_ code: PixCode) {
        self.init(status: PixCheck.Status(code.status))
        guard status != .malformed else { return }
        dynamic = code.dynamic
        singleUse = code.singleUse
        key = code.key
        keyType = code.keyType.map(PixCheck.KeyType.init)
        url = code.url
        name = code.name
        city = code.city
        amount = code.amount
        txid = code.txid
        info = code.info
    }
}

extension PixCheck.Status {
    init(_ status: PixCodeStatus) {
        switch status {
        case .valid: self = .valid
        case .badCrc: self = .badCrc
        case .malformed: self = .malformed
        }
    }
}

extension PixCheck.KeyType {
    init(_ type: PixKeyType) {
        switch type {
        case .cpf: self = .cpf
        case .cnpj: self = .cnpj
        case .phone: self = .phone
        case .email: self = .email
        case .random: self = .random
        case .unknown: self = .unknown
        }
    }
}
