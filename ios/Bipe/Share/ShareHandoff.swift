// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The pending share of spec share-to-chat (iOS): the share extension writes the shared items into the app group,
/// and the app takes them on its next open. One use, 15 minutes, a random ID, protected files out of backups,
/// an atomic claim. No content ever goes into a URL. Compiled into the app and the share extension (project.yml).
enum ShareHandoff {
    struct Item: Codable, Equatable {
        /// Plain text or a web link as text.
        var text: String?
        /// A file, by its path inside the pending folder ("<index>/<name>").
        var path: String?
        var mimeType: String?
    }

    struct Manifest: Codable, Equatable {
        let id: String
        let createdAt: Date
        var items: [Item]
        /// The share had more than 10 items; only the first 10 are here.
        var droppedOverLimit: Bool
    }

    /// A claimed share: the folder belongs to the app until it deletes it.
    struct Claimed {
        let manifest: Manifest
        let folder: URL

        /// The joined text items, in provider order.
        var text: String {
            manifest.items.compactMap(\.text).joined(separator: "\n")
        }

        /// The file items, in provider order.
        var files: [URL] {
            manifest.items.compactMap(\.path).map { folder.appendingPathComponent($0) }
        }

        func delete() {
            try? FileManager.default.removeItem(at: folder)
        }
    }

    static let maxItems = 10
    static let lifetime: TimeInterval = 15 * 60
    private static let manifestFile = "share.json"

    private static var root: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup)?
            .appendingPathComponent("SharePending", isDirectory: true)
    }

    /// Writes one pending share in the extension. The app never sees it before commit.
    final class Writer {
        let id = UUID().uuidString
        let staging: URL
        private var items: [Item] = []

        init() throws {
            guard let root = ShareHandoff.root else { throw CocoaError(.fileWriteNoPermission) }
            staging = root.appendingPathComponent("staging-\(id)", isDirectory: true)
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true,
                                                    attributes: [.protectionKey: FileProtectionType.complete])
        }

        /// A free destination for the file of item `index`, in its own subfolder so that the file keeps its name.
        func destination(index: Int, name: String) throws -> (url: URL, path: String) {
            let safe = Self.safeName(name)
            let folder = staging.appendingPathComponent(String(index), isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            return (folder.appendingPathComponent(safe), "\(index)/\(safe)")
        }

        func addText(_ text: String) {
            items.append(Item(text: text, path: nil, mimeType: nil))
        }

        func addFile(path: String, mimeType: String?) {
            items.append(Item(text: nil, path: path, mimeType: mimeType))
        }

        var isEmpty: Bool { items.isEmpty }

        /// Makes the share visible to the app, by one rename after a complete write.
        func commit(droppedOverLimit: Bool) throws {
            guard let root = ShareHandoff.root else { throw CocoaError(.fileWriteNoPermission) }
            let manifest = Manifest(id: id, createdAt: Date(), items: items, droppedOverLimit: droppedOverLimit)
            do {
                let data = try JSONEncoder().encode(manifest)
                try data.write(to: staging.appendingPathComponent(ShareHandoff.manifestFile), options: [.atomic, .completeFileProtection])
                var folder = staging
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try folder.setResourceValues(values)
                try FileManager.default.moveItem(at: staging, to: root.appendingPathComponent("pending-\(id)", isDirectory: true))
            } catch {
                discard()
                throw error
            }
        }

        /// Action cancel: deletes every copy.
        func discard() {
            try? FileManager.default.removeItem(at: staging)
        }

        private static func safeName(_ name: String) -> String {
            let cleaned = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
            let trimmed = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed.hasPrefix(".") ? "file" + trimmed : trimmed
        }
    }

    /// The app's next open: takes the newest share still valid, by an atomic rename, and deletes the rest.
    static func claim(now: Date = Date()) -> Claimed? {
        guard let root,
              let folders = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return nil }
        var candidates: [(Manifest, URL)] = []
        for folder in folders {
            let name = folder.lastPathComponent
            guard name.hasPrefix("pending-"),
                  let data = try? Data(contentsOf: folder.appendingPathComponent(manifestFile)),
                  let manifest = try? JSONDecoder().decode(Manifest.self, from: data),
                  now.timeIntervalSince(manifest.createdAt) < lifetime else {
                // An expired or broken share goes now; staging and claimed folders once they are stale.
                if name.hasPrefix("pending-") || isStale(folder, now: now) {
                    try? FileManager.default.removeItem(at: folder)
                }
                continue
            }
            candidates.append((manifest, folder))
        }
        candidates.sort { $0.0.createdAt > $1.0.createdAt }
        for (index, candidate) in candidates.enumerated() {
            if index > 0 {
                try? FileManager.default.removeItem(at: candidate.1)
                continue
            }
            let claimed = root.appendingPathComponent("claimed-\(candidate.0.id)", isDirectory: true)
            guard (try? FileManager.default.moveItem(at: candidate.1, to: claimed)) != nil else { return nil }
            return Claimed(manifest: candidate.0, folder: claimed)
        }
        return nil
    }

    /// Signed out: nothing is kept.
    static func deleteAll() {
        guard let root else { return }
        try? FileManager.default.removeItem(at: root)
    }

    private static func isStale(_ folder: URL, now: Date) -> Bool {
        let created = (try? folder.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
        return now.timeIntervalSince(created) >= lifetime
    }
}
