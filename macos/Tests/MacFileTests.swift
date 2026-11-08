// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UniformTypeIdentifiers
import XCTest
@testable import BipeMac

final class MacFileTests: XCTestCase {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testSenderNamesCannotHideExtensionsOrBecomePaths() {
        let name = MacFileName("../../private\\invoice\u{202E}\n.txt.exe", mimeType: "image/png")
        XCTAssertEqual(name.filename, "invoice.txt.exe")
        XCTAssertFalse(name.type.conforms(to: .image))
        XCTAssertEqual(MacFileName("backup.tar.gz", mimeType: "image/png").filename, "backup.tar.gz")
        XCTAssertEqual(MacFileName("notes", mimeType: "text/plain").filename, "notes.txt")
        XCTAssertTrue(MacFileName("....").filename.hasSuffix(".bin"))
        let long = MacFileName(String(repeating: "📝", count: 200) + ".pdf")
        XCTAssertLessThan(long.filename.utf8.count, 255)
        XCTAssertTrue(long.filename.hasSuffix(".pdf"))
        XCTAssertFalse(MacFileName(".secret\u{2028}:name.pdf").filename.hasPrefix("."))
    }

    func testOversizeSelectionIsRejectedBeforeCreatingAnUploadCopy() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("receipt.pdf")
        let bytes = Data(repeating: 7, count: 16)
        try bytes.write(to: source)
        let uploads = root.appendingPathComponent("Uploads")
        XCTAssertThrowsError(try MacTransferFiles.prepare(source, maxUploadSize: 15, under: uploads)) { error in
            guard case MacTransferFiles.Failure.tooLarge(15) = error else { return XCTFail("Expected the homeserver limit") }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: uploads.path))
        XCTAssertEqual(try Data(contentsOf: source), bytes)
    }

    func testPreparedFileKeepsItsBytesTypeAndPrivateLifetime() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("data.csv")
        let bytes = Data("a,b\n1,2\n".utf8)
        try bytes.write(to: source)
        var prepared: MacPreparedFile? = try MacTransferFiles.prepare(source, maxUploadSize: 100, under: root.appendingPathComponent("Uploads"))
        let output = try XCTUnwrap(prepared?.file)
        let work = try XCTUnwrap(prepared?.folder)
        XCTAssertEqual(try Data(contentsOf: output), bytes)
        XCTAssertEqual(output.pathExtension, "csv")
        XCTAssertEqual(prepared?.info.size, UInt64(bytes.count))
        XCTAssertNotEqual(prepared?.info.mimetype, "image/jpeg")
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: work.path)[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: output.path)[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        XCTAssertEqual(try work.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        prepared = nil
        XCTAssertFalse(FileManager.default.fileExists(atPath: work.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    func testDirectoriesAndSymbolicLinksDoNotBecomeFileUploads() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("file.bin")
        try Data([1]).write(to: source)
        let link = root.appendingPathComponent("link.bin")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: source)
        XCTAssertThrowsError(try MacTransferFiles.prepare(root, maxUploadSize: nil))
        XCTAssertThrowsError(try MacTransferFiles.prepare(link, maxUploadSize: nil))
    }

    func testPreviewLeaseRetainsTheSDKHandleAndRemovesItsFolderOnClose() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let work = try MacTransferFiles.folder(under: root)
        let file = work.appendingPathComponent("preview.pdf")
        try Data([1]).write(to: file)
        weak var weakHandle: MediaFileHandle?
        var preview: MacDownloadedFile?
        do {
            // Documented SDK fixture initializer. No fixture method is called or passed back to Rust.
            let handle = MediaFileHandle(noHandle: .init())
            weakHandle = handle
            preview = MacDownloadedFile(folder: work, file: file, handle: handle)
        }
        XCTAssertNotNil(weakHandle)
        XCTAssertEqual(preview?.file, file)
        preview = nil
        XCTAssertNil(weakHandle)
        XCTAssertFalse(FileManager.default.fileExists(atPath: work.path))
    }

    @MainActor
    func testCancelledPreparationLeavesNoUploadCopy() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("file.bin")
        try Data([1]).write(to: source)
        let uploads = root.appendingPathComponent("Uploads")
        let task = Task { try MacTransferFiles.prepare(source, maxUploadSize: nil, under: uploads) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled file must not be prepared") }
        catch is CancellationError {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: uploads.path))
    }

    func testSavedFileIsQuarantinedAndQuarantineFailurePreservesExistingOutput() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source.pdf"), output = root.appendingPathComponent("saved.pdf")
        try Data("new bytes".utf8).write(to: source)
        try Data("previous bytes".utf8).write(to: output)
        XCTAssertThrowsError(try MacTransferFiles.save(source, to: output, quarantine: { _ in throw CocoaError(.fileWriteNoPermission) }))
        XCTAssertEqual(try String(contentsOf: output, encoding: .utf8), "previous bytes")
        try MacTransferFiles.save(source, to: output)
        XCTAssertEqual(try Data(contentsOf: output), try Data(contentsOf: source))
        let values = try output.resourceValues(forKeys: [.quarantinePropertiesKey, .hasHiddenExtensionKey])
        XCTAssertNotNil(values.quarantineProperties)
        XCTAssertEqual(values.hasHiddenExtension, false)
    }

    func testFileNamesCaptionsAndFormattedDestinationsReachScamChecks() throws {
        let source = try MediaSource.fromUrl(url: "mxc://example.invalid/file")
        let content = FileMessageContent(filename: "urgente.pdf", caption: "Read this", formattedCaption:
            FormattedBody(format: .html, body: "<a href='https://example.invalid/payment'>Read this</a>"), source: source,
            info: FileInfo(mimetype: "application/pdf", size: 42, thumbnailInfo: nil, thumbnailSource: nil))
        let payload = MacMessagePayload(type: .file(content: content), fallback: "urgente.pdf", eventJson: nil)
        XCTAssertEqual(payload.file?.name.filename, "urgente.pdf")
        XCTAssertEqual(payload.body, "Read this")
        XCTAssertTrue(payload.safetyText.contains("urgente.pdf"))
        XCTAssertTrue(payload.safetyText.contains("https://example.invalid/payment"))
        XCTAssertNotNil(payload.warning(including: nil, isOwn: false, eventID: "file-warning"))
        let deleted = try XCTUnwrap(MacMessagePayload(kind: .redacted, eventJson: nil))
        XCTAssertNil(deleted.file)
    }

    @MainActor
    func testClearingAFileCaptionUsesAMediaEditInsteadOfChangingItsMessageType() throws {
        let source = try MediaSource.fromUrl(url: "mxc://example.invalid/file")
        var message = MacMessage(id: "file", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "Caption", richText: BipeMessageText(body: "Caption"), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: true, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$file", canReply: true, canReact: true, reactions: [])
        message.file = MacFile(content: FileMessageContent(filename: "file.pdf", caption: "Caption", formattedCaption: nil,
                                                         source: source, info: nil))
        guard case .mediaCaption(let caption, _, _) = try message.editedContent("") else { return XCTFail("Expected a media-caption edit") }
        XCTAssertEqual(caption, "")
    }
}
