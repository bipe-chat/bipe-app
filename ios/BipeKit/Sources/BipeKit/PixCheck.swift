// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CoreImage
import Foundation

/// "Conferir no Bipe" phase 1 (bipe ADR 0091): the Pix code and the Pix keys in text that the user
/// shared from another app. Runs on the device only. Must match bipe-shared/test-vectors/pix-check.json.
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
        let points = text.unicodeScalars.map(String.init)
        let found = findBrCode(points)
        let rest = found.map { points[..<$0.start].joined() + " " + points[$0.end...].joined() } ?? text
        return PixCheck(code: found?.code,
                        keys: keys(in: rest),
                        warning: ScamDetector.warningKind(forMatchIDs: ScamDetector.matchIDs(in: rest), message: rest, skipping: skippedWarnings))
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
        if key.contains("@") {
            return .email
        }
        if matches(key, "^\\+[0-9]{12,13}$") {
            return .phone
        }
        if matches(key, "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") {
            return .random
        }
        if matches(key, "^[0-9]{11}$") {
            return .cpf
        }
        if matches(key, "^[0-9A-Z]{12}[0-9]{2}$") {
            return .cnpj
        }
        return .unknown
    }
    
    // MARK: - BR Code
    
    private static let pixGUI = "br.gov.bcb.pix"
    
    private struct Field {
        let id: String
        let value: String
        let start: Int
        let end: Int
    }
    
    /// EMV TLV: two-digit ID, two-digit length, value. Lengths count code points. Nil when the shape is wrong.
    private static func readTLV(_ points: [String], from: Int, until: Int) -> [Field]? {
        var fields: [Field] = []
        var index = from
        while index < until {
            guard index + 4 <= until else { return nil }
            let head = points[index..<index + 4].joined()
            guard head.utf8.count == 4, head.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            let id = String(head.prefix(2))
            let length = Int(head.suffix(2)) ?? 0
            guard index + 4 + length <= until else { return nil }
            fields.append(Field(id: id, value: points[index + 4..<index + 4 + length].joined(), start: index, end: index + 4 + length))
            index += 4 + length
            if id == "63" {
                break
            }
        }
        return fields
    }
    
    private static func subfields(_ value: String) -> [String: String]? {
        let points = value.unicodeScalars.map(String.init)
        guard let fields = readTLV(points, from: 0, until: points.count) else { return nil }
        return fields.reduce(into: [:]) { $0[$1.id] = $1.value }
    }
    
    private static func findBrCode(_ points: [String]) -> (start: Int, end: Int, code: Code)? {
        guard points.count >= 6 else { return nil }
        for start in 0...points.count - 6 where points[start..<start + 6].joined() == "000201" {
            guard asciiLower(points[start...].joined()).contains(pixGUI) else { return nil }
            guard let fields = readTLV(points, from: start, until: points.count) else {
                return (start, points.count, Code(status: .malformed))
            }
            return (start, fields[fields.count - 1].end, parse(points, start: start, fields: fields))
        }
        return nil
    }
    
    private static func parse(_ points: [String], start: Int, fields: [Field]) -> Code {
        let malformed = Code(status: .malformed)
        let crcField = fields[fields.count - 1]
        guard fields[0].id == "00", fields[0].value == "01" else { return malformed }
        guard crcField.id == "63", crcField.value.unicodeScalars.count == 4 else { return malformed }
        let byID = fields.reduce(into: [String: String]()) { $0[$1.id] = $1.value }
        
        var account: [String: String]?
        for field in fields {
            guard let number = Int(field.id), (26...51).contains(number), let subs = subfields(field.value) else { continue }
            if asciiLower(subs["00"] ?? "") == pixGUI {
                account = subs
                break
            }
        }
        guard let account else { return malformed }
        let key = account["01"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = account["25"]
        guard key != nil || url != nil else { return malformed }
        
        var txid: String?
        if let extra = byID["62"] {
            guard let subs = subfields(extra) else { return malformed }
            txid = subs["05"].flatMap { $0 == "***" ? nil : $0 }
        }
        
        let signed = points[start..<crcField.start + 4].joined()
        var code = Code(status: crc16(signed) == crcField.value.uppercased() ? .valid : .badCrc)
        code.dynamic = url != nil
        code.singleUse = byID["01"] == "12"
        code.key = key
        code.keyType = key.map(keyType(of:))
        code.url = url
        code.name = byID["59"]
        code.city = byID["60"]
        code.amount = byID["54"]
        code.txid = txid
        code.info = account["02"]
        return code
    }
    
    /// CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the UTF-8 bytes, as 4 uppercase hex digits.
    static func crc16(_ text: String) -> String {
        var crc: UInt16 = 0xFFFF
        for byte in text.utf8 {
            crc ^= UInt16(byte) << 8
            for _ in 0..<8 {
                crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1
            }
        }
        return String(format: "%04X", crc)
    }
    
    // MARK: - Keys in text
    
    private static let keyExpressions: [(KeyType, NSRegularExpression)] = BipeRegistries.pixKeyPatterns.compactMap { pattern in
        guard let type = KeyType(rawValue: pattern.id), let expression = try? NSRegularExpression(pattern: pattern.regex) else { return nil }
        return (type, expression)
    }
    
    private static func keys(in text: String) -> [Key] {
        let nsText = text as NSString
        var spans: [NSRange: [KeyType]] = [:]
        for (type, expression) in keyExpressions {
            for match in expression.matches(in: text, range: NSRange(location: 0, length: nsText.length)) {
                spans[match.range, default: []].append(type)
            }
        }
        return spans
            .sorted { $0.key.location != $1.key.location ? $0.key.location < $1.key.location : $0.key.length > $1.key.length }
            .map { Key(value: nsText.substring(with: $0.key), types: $0.value) }
    }
    
    // MARK: - Helpers
    
    private static func matches(_ text: String, _ pattern: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }
    
    /// Lowercases A-Z only, like the reference.
    private static func asciiLower(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.map { (0x41...0x5A).contains($0.value) ? Unicode.Scalar($0.value + 0x20)! : $0 }))
    }
}
