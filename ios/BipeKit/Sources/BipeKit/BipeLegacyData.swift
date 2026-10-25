// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Security
import SQLite3

/// Data of the earlier Bipe app (the fork) on this iPhone, for the switch to the fork's IDs (bipe-chat/bipe#603,
/// bipe/docs/client-switch-plan.md step 3; spec account-start legacyKept).
///
/// - Read only: nothing here writes, moves or deletes a keychain item or a file.
/// - `isPresent` is true when a legacy read group (the Team-ID group, the fork's group) holds an item whose service this
///   app never writes. BipeKeychainMove already copies the items this app knows.
/// - `report()` is the structure report for the rehearsal: names, sizes, value types, JSON key names and SQLite table
///   names. Never a value. Matrix IDs show as their server name only.
public nonisolated struct BipeLegacyData: Sendable {
    private let groups: [String]
    private let ownServices: Set<String>
    private let keychain: any CredentialKeychain

    /// The legacy read groups come from Info.plist (BipeLegacyKeychainReadGroups); `ownServices` are the services this
    /// app writes (KeychainMove.services).
    public init(ownServices: [String]) {
        self.init(groups: Bundle.main.object(forInfoDictionaryKey: "BipeLegacyKeychainReadGroups") as? [String] ?? [],
                  ownServices: ownServices,
                  keychain: SystemCredentialKeychain())
    }

    init(groups: [String], ownServices: [String], keychain: any CredentialKeychain) {
        self.groups = groups.filter { !$0.isEmpty && !$0.contains("$(") }
        self.ownServices = Set(ownServices + [BipeKeychainMove.markerService])
        self.keychain = keychain
    }

    /// One keychain item that this app never wrote. No value.
    public struct Item: Sendable, Equatable, CustomStringConvertible {
        public let group: String
        public let service: String
        /// The account attribute, redacted (`BipeLegacyShape.redact`).
        public let account: String
        public let byteCount: Int

        public var description: String { "BipeLegacyData.Item(\(service), \(byteCount) bytes)" }
    }

    /// Items in the legacy read groups whose service this app never writes.
    public func foreignItems() -> [Item] {
        groups.flatMap { group in
            records(in: group).compactMap { record -> Item? in
                let service = record[kSecAttrService as String] as? String ?? ""
                guard !ownServices.contains(service) else { return nil }
                let account = record[kSecAttrAccount as String] as? String ?? ""
                let data = record[kSecValueData as String] as? Data ?? Data()
                return Item(group: group, service: service, account: BipeLegacyShape.redact(account), byteCount: data.count)
            }
        }
    }

    /// Spec account-start legacyKept.
    public var isPresent: Bool { !foreignItems().isEmpty }

    /// The structure report (legacy-data.json): every item in the legacy groups (own services marked), then the folders
    /// given (the app group, the app's Library and Documents), then the defaults suites given. Keys sorted, so two runs on
    /// one phone give the same file.
    public func report(folders: [String: URL], defaults: [String: UserDefaults]) -> Data {
        var keychainPart: [[String: Any]] = []
        for group in groups {
            for record in records(in: group) {
                let service = record[kSecAttrService as String] as? String ?? ""
                let data = record[kSecValueData as String] as? Data ?? Data()
                keychainPart.append([
                    "group": group,
                    "service": service,
                    "own": ownServices.contains(service),
                    "account": BipeLegacyShape.redact(record[kSecAttrAccount as String] as? String ?? ""),
                    "synchronizable": (record[kSecAttrSynchronizable as String] as? Bool) ?? false,
                    "accessible": record[kSecAttrAccessible as String] as? String ?? "",
                    "value": BipeLegacyShape.describe(data),
                ])
            }
        }
        var folderPart: [String: Any] = [:]
        for (name, url) in folders {
            folderPart[name] = BipeLegacyShape.tree(at: url)
        }
        var defaultsPart: [String: Any] = [:]
        for (name, suite) in defaults {
            var keys: [String: String] = [:]
            for (key, value) in suite.persistentDomain(forName: name) ?? [:] {
                keys[BipeLegacyShape.redact(key)] = BipeLegacyShape.typeName(value)
            }
            defaultsPart[name] = keys
        }
        let report: [String: Any] = [
            "format": 1,
            "keychain": keychainPart,
            "folders": folderPart,
            "defaults": defaultsPart,
        ]
        return (try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])) ?? Data()
    }

    private func records(in group: String) -> [[String: Any]] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccessGroup as String: group,
                                    kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
                                    kSecReturnData as String: true,
                                    kSecReturnAttributes as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitAll]
        let (status, result) = keychain.copyMatching(query)
        return status == errSecSuccess ? result as? [[String: Any]] ?? [] : []
    }
}

/// Structure-only descriptions for the legacy report. Every function drops values.
public nonisolated enum BipeLegacyShape {
    /// The deepest JSON or property list level the report describes.
    static let maxDepth = 4
    /// The most folder entries one tree lists.
    static let maxEntries = 400

    /// A Matrix ID shows as `@…:server`; any other text shows as its length only, so no handle, name or secret leaks.
    /// Known service-like names (letters, digits, dots, dashes, underscores, no `@`) stay, so the report can name them.
    public static func redact(_ text: String) -> String {
        if text.hasPrefix("@"), let colon = text.firstIndex(of: ":") {
            return "@…:" + text[text.index(after: colon)...]
        }
        if text.contains("@") { return "text(\(text.count))" }
        let plain = text.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || "._-".unicodeScalars.contains($0) }
        if plain, text.count <= 64, !looksRandom(text) { return text }
        return "text(\(text.count))"
    }

    /// The shape of a keychain value: JSON or property list key names and value types, else text or binary with its length.
    public static func describe(_ data: Data) -> Any {
        if let json = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]), json is [String: Any] || json is [Any] {
            return ["json": shape(json, depth: 0)]
        }
        if data.starts(with: Data("bplist".utf8)),
           let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) {
            return ["plist": shape(plist, depth: 0)]
        }
        if String(data: data, encoding: .utf8) != nil { return "text(\(data.count))" }
        return "binary(\(data.count))"
    }

    static func shape(_ value: Any, depth: Int) -> Any {
        switch value {
        case let object as [String: Any]:
            guard depth < maxDepth else { return "object(\(object.count))" }
            var out: [String: Any] = [:]
            for (key, inner) in object { out[redact(key)] = shape(inner, depth: depth + 1) }
            return out
        case let array as [Any]:
            guard depth < maxDepth, let first = array.first else { return "array(\(array.count))" }
            return ["array": array.count, "first": shape(first, depth: depth + 1)]
        default:
            return typeName(value)
        }
    }

    /// The type of a value, with the length of a text or data value. Never the value.
    public static func typeName(_ value: Any) -> String {
        switch value {
        case let text as String: return "string(\(text.count))"
        case let data as Data: return "data(\(data.count))"
        case let number as NSNumber:
            return CFGetTypeID(number) == CFBooleanGetTypeID() ? "bool" : "number"
        case is Date: return "date"
        case let array as [Any]: return "array(\(array.count))"
        case let object as [String: Any]: return "object(\(object.count))"
        case is NSNull: return "null"
        default: return "other"
        }
    }

    /// Every entry under `root` (at most `maxEntries`), by redacted relative path: size, and for a SQLite file its table
    /// names. A SQLite file opens read only and immutable, so nothing on disk changes.
    public static func tree(at root: URL) -> [String: Any] {
        let manager = FileManager.default
        guard let walker = manager.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
                                              options: [], errorHandler: { _, _ in true }) else { return [:] }
        var out: [String: Any] = [:]
        let base = root.resolvingSymlinksInPath().pathComponents.count
        for case let url as URL in walker {
            guard out.count < maxEntries else {
                out["…"] = "more entries"
                break
            }
            let parts = url.resolvingSymlinksInPath().pathComponents.dropFirst(base).map(redact)
            let key = parts.joined(separator: "/")
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            if values?.isDirectory == true {
                out[key + "/"] = "folder"
                continue
            }
            var entry: [String: Any] = ["bytes": values?.fileSize ?? 0]
            if let kind = sqliteKind(url) { entry["sqlite"] = kind }
            out[key] = entry
        }
        return out
    }

    /// "plain" with table names, "not plain" for a database file without the SQLite header (encrypted or other), nil
    /// for any other file.
    static func sqliteKind(_ url: URL) -> Any? {
        let databaseExtensions: Set<String> = ["sqlite", "sqlite3", "db"]
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        let header = (try? handle.read(upToCount: 16)) ?? Data()
        try? handle.close()
        guard header == Data("SQLite format 3\u{0}".utf8) else {
            return databaseExtensions.contains(url.pathExtension.lowercased()) ? "not plain" : nil
        }
        return ["tables": tables(url)]
    }

    private static func tables(_ url: URL) -> [String] {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.scheme = "file"
        components?.queryItems = [URLQueryItem(name: "mode", value: "ro"), URLQueryItem(name: "immutable", value: "1")]
        guard let uri = components?.string else { return [] }
        var db: OpaquePointer?
        defer { sqlite3_close(db) }
        guard sqlite3_open_v2(uri, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil) == SQLITE_OK else { return [] }
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", -1, &statement, nil) == SQLITE_OK else {
            return []
        }
        var names: [String] = []
        while sqlite3_step(statement) == SQLITE_ROW, names.count < 100 {
            if let text = sqlite3_column_text(statement, 0) { names.append(redact(String(cString: text))) }
        }
        return names
    }

    /// A long run of hex or base64 characters (a key, a token, a hash) is shown as its length only.
    private static func looksRandom(_ text: String) -> Bool {
        guard text.count >= 20 else { return false }
        let digits = text.filter(\.isNumber).count
        let letters = text.filter(\.isLetter).count
        return digits >= 4 && letters >= 4 && !text.contains(".")
    }
}
