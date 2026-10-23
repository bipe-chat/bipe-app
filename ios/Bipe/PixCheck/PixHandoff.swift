// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The pending preview of spec pix-check-receive (iOS handoff): only an explicit Send to a chat in the share
/// extension saves it in the app group; the app takes it on a later open. One use, five minutes, a random ID,
/// protected and out of backups. No raw content ever goes into a URL.
/// Compiled into the app and the share extension (project.yml).
enum PixHandoff {
    struct Preview: Codable, Equatable {
        let id: String
        let createdAt: Date
        /// The original shared text, or the decoded payload when the input was an image.
        var text: String?
        /// The original shared image, by file name inside the preview folder.
        var imageName: String?
    }

    /// A claimed preview: the folder belongs to the app until it deletes it.
    struct Claimed {
        let preview: Preview
        let folder: URL

        var imageURL: URL? {
            preview.imageName.map { folder.appendingPathComponent($0) }
        }

        func delete() {
            try? FileManager.default.removeItem(at: folder)
        }
    }

    static let lifetime: TimeInterval = 5 * 60
    private static let accountKey = "bipe.pixCheck.accountAvailable"
    private static let previewFile = "preview.json"

    /// accountAvailable of spec pix-check-result: the app writes it, the extension reads it. A hint only:
    /// the app still asks for chat and confirmation with the account it has.
    static var accountAvailable: Bool {
        get { UserDefaults(suiteName: AppConfig.appGroup)?.bool(forKey: accountKey) ?? false }
        set { UserDefaults(suiteName: AppConfig.appGroup)?.set(newValue, forKey: accountKey) }
    }

    private static var root: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup)?
            .appendingPathComponent("PixPending", isDirectory: true)
    }

    /// Action sendToChat in the extension: saves the preview. Throws when the app group is not writable.
    static func save(text: String?, image: URL?) throws {
        guard let root else { throw CocoaError(.fileWriteNoPermission) }
        let id = UUID().uuidString
        let staging = root.appendingPathComponent("staging-\(id)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true,
                                                attributes: [.protectionKey: FileProtectionType.complete])
        var preview = Preview(id: id, createdAt: Date(), text: text, imageName: nil)
        do {
            if let image {
                let name = "image." + (image.pathExtension.isEmpty ? "jpg" : image.pathExtension)
                try FileManager.default.copyItem(at: image, to: staging.appendingPathComponent(name))
                preview.imageName = name
            }
            let data = try JSONEncoder().encode(preview)
            try data.write(to: staging.appendingPathComponent(previewFile), options: [.atomic, .completeFileProtection])
            var folder = staging
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            // Ready only after a complete write: the app never sees a half-written preview.
            try FileManager.default.moveItem(at: staging, to: root.appendingPathComponent("pending-\(id)", isDirectory: true))
        } catch {
            try? FileManager.default.removeItem(at: staging)
            throw error
        }
    }

    /// The app's later open: takes the newest preview still valid, by an atomic rename, and deletes the rest.
    static func claim(now: Date = Date()) -> Claimed? {
        guard let root,
              let folders = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return nil }
        var candidates: [(Preview, URL)] = []
        for folder in folders {
            let name = folder.lastPathComponent
            guard name.hasPrefix("pending-"),
                  let data = try? Data(contentsOf: folder.appendingPathComponent(previewFile)),
                  let preview = try? JSONDecoder().decode(Preview.self, from: data),
                  now.timeIntervalSince(preview.createdAt) < lifetime else {
                // An expired or half-written preview goes now; staging and claimed folders once they are stale.
                if name.hasPrefix("pending-") || isStale(folder, now: now) {
                    try? FileManager.default.removeItem(at: folder)
                }
                continue
            }
            candidates.append((preview, folder))
        }
        candidates.sort { $0.0.createdAt > $1.0.createdAt }
        for (index, candidate) in candidates.enumerated() {
            if index > 0 {
                try? FileManager.default.removeItem(at: candidate.1)
                continue
            }
            let claimed = root.appendingPathComponent("claimed-\(candidate.0.id)", isDirectory: true)
            guard (try? FileManager.default.moveItem(at: candidate.1, to: claimed)) != nil else { return nil }
            return Claimed(preview: candidate.0, folder: claimed)
        }
        return nil
    }

    private static func isStale(_ folder: URL, now: Date) -> Bool {
        let created = (try? folder.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
        return now.timeIntervalSince(created) >= lifetime
    }
}
