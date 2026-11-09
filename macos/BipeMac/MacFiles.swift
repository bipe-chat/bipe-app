// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Foundation
import MatrixRustSDK
import UniformTypeIdentifiers

struct MacFile {
    let content: FileMessageContent
    var name: MacFileName { MacFileName(content.filename, mimeType: content.info?.mimetype) }
    var sizeLabel: String? { content.info?.size.map { ByteCountFormatter.string(fromByteCount: Int64(clamping: $0), countStyle: .file) } }
}

/// Keep the last extension visible, including a potentially executable one. Sender text never becomes a path.
struct MacFileName {
    let filename: String
    let type: UTType

    init(_ raw: String, mimeType: String? = nil) {
        let last = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        let scalars = last.unicodeScalars.filter {
            switch $0.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator: false
            default: $0 != ":"
            }
        }
        let clean = String(String.UnicodeScalarView(scalars)).trimmingCharacters(in: CharacterSet(charactersIn: ". ").union(.whitespacesAndNewlines))
        let originalExtension = (clean as NSString).pathExtension
        let validExtension = !originalExtension.isEmpty && originalExtension.utf8.count <= 32 && originalExtension.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) && $0.isASCII || "_+-".unicodeScalars.contains($0)
        }
        let suffix = validExtension ? originalExtension : (mimeType.flatMap { UTType(mimeType: $0)?.preferredFilenameExtension } ?? "bin")
        var stem = originalExtension.isEmpty ? clean : (clean as NSString).deletingPathExtension
        stem = stem.trimmingCharacters(in: CharacterSet(charactersIn: ". ").union(.whitespacesAndNewlines))
        if stem.isEmpty { stem = BipeStrings.appMediaFile }
        while stem.utf8.count > 180 { stem.removeLast() }
        filename = stem + "." + suffix
        type = UTType(filenameExtension: suffix) ?? .data
    }
}

final class MacPreparedFile: Identifiable, Sendable {
    let id = UUID()
    let folder: URL
    let file: URL
    let info: FileInfo
    init(folder: URL, file: URL, info: FileInfo) { self.folder = folder; self.file = file; self.info = info }
    func remove() { try? FileManager.default.removeItem(at: folder) }
    deinit { remove() }
}

/// The SDK owns its decrypted file; the preview owns this lease and its quarantined, safely named copy.
final class MacDownloadedFile {
    let folder: URL
    let file: URL
    private let handle: MediaFileHandle
    init(folder: URL, file: URL, handle: MediaFileHandle) { self.folder = folder; self.file = file; self.handle = handle }
    func remove() { try? FileManager.default.removeItem(at: folder) }
    deinit { remove() }
}

enum MacTransferFiles {
    enum Failure: Error { case tooLarge(UInt64) }

    static var root: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat.bipe.app.mac/Uploads", isDirectory: true)
    }

    static func folder(under root: URL = MacTransferFiles.root) throws -> URL {
        var url = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        do {
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try url.setResourceValues(values)
            return url
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }

    static func prepare(_ source: URL, maxUploadSize: UInt64?, under root: URL = MacTransferFiles.root, filename: String? = nil) throws -> MacPreparedFile {
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard source.isFileURL, values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size >= 0 else { throw CocoaError(.fileReadUnsupportedScheme) }
        try checkSize(UInt64(size), limit: maxUploadSize)
        try Task.checkCancellation()
        let folder = try Self.folder(under: root)
        do {
            let name = MacFileName(filename ?? source.lastPathComponent)
            let file = folder.appendingPathComponent(name.filename)
            try FileManager.default.copyItem(at: source, to: file)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            let copiedSize = UInt64(max(0, try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0))
            try checkSize(copiedSize, limit: maxUploadSize)
            try Task.checkCancellation()
            let info = FileInfo(mimetype: name.type.preferredMIMEType ?? "application/octet-stream", size: copiedSize,
                                thumbnailInfo: nil, thumbnailSource: nil)
            return MacPreparedFile(folder: folder, file: file, info: info)
        } catch { try? FileManager.default.removeItem(at: folder); throw error }
    }

    static func checkSize(_ size: UInt64, limit: UInt64?) throws {
        if let limit, size > limit { throw Failure.tooLarge(limit) }
    }

    /// Quarantine the staged copy before replacing a user's chosen output. Never damage an existing output on failure.
    static func save(_ source: URL, to destination: URL, quarantine: (URL) throws -> Void = MacSavedImage.quarantine) throws {
        let manager = FileManager.default
        let staging = try manager.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: destination, create: true)
        defer { try? manager.removeItem(at: staging) }
        var copy = staging.appendingPathComponent(destination.lastPathComponent)
        try manager.copyItem(at: source, to: copy)
        try quarantine(copy)
        var values = URLResourceValues(); values.hasHiddenExtension = false
        try copy.setResourceValues(values)
        if manager.fileExists(atPath: destination.path) {
            _ = try manager.replaceItemAt(destination, withItemAt: copy, backupItemName: nil, options: .usingNewMetadataOnly)
        } else { try manager.moveItem(at: copy, to: destination) }
    }
}

