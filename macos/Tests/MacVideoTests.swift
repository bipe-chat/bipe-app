// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import AVFoundation
import BipeKit
import CoreVideo
import ImageIO
import MatrixRustSDK
import SwiftUI
import UniformTypeIdentifiers
import XCTest
@testable import BipeMac

final class MacVideoTests: XCTestCase {
    private func folder() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    /// An original synthetic clip. No camera, account, network, external fixture or local build is used.
    private func movie(in folder: URL) async throws -> URL {
        let url = folder.appendingPathComponent("video.mov")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let metadata = AVMutableMetadataItem()
        metadata.identifier = .commonIdentifierDescription
        metadata.value = "Bipe synthetic metadata" as NSString
        writer.metadata = [metadata]
        let width = 1600, height = 900
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
            sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        writer.startSession(atSourceTime: .zero)
        var buffer: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32ARGB,
            [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary, &buffer), kCVReturnSuccess)
        let pixels = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(pixels, [])
        if let base = CVPixelBufferGetBaseAddress(pixels) { memset(base, 120, CVPixelBufferGetBytesPerRow(pixels) * height) }
        CVPixelBufferUnlockBaseAddress(pixels, [])
        for frame in 0..<3 {
            for _ in 0..<250 {
                if input.isReadyForMoreMediaData { break }
                try await Task.sleep(for: .milliseconds(20))
            }
            XCTAssertTrue(input.isReadyForMoreMediaData)
            guard adaptor.append(pixels, withPresentationTime: CMTime(value: Int64(frame), timescale: 3)) else {
                throw writer.error ?? CocoaError(.fileWriteUnknown)
            }
        }
        writer.endSession(atSourceTime: CMTime(value: 1, timescale: 1))
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        return url
    }

    private func content(caption: String? = nil) throws -> VideoMessageContent {
        VideoMessageContent(filename: "urgent.mov", caption: caption,
            formattedCaption: FormattedBody(format: .html, body: "<a href='https://example.invalid/payment'>Read this</a>"),
            source: try MediaSource.fromUrl(url: "mxc://example.invalid/video"),
            info: VideoInfo(duration: 65, height: 900, width: 1600, mimetype: "video/quicktime", size: 42,
                            thumbnailInfo: nil, thumbnailSource: nil, blurhash: nil))
    }

    private func lease(_ source: URL, under root: URL) throws -> MacDownloadedFile {
        let folder = try MacTransferFiles.folder(under: root)
        let file = folder.appendingPathComponent(source.lastPathComponent)
        try FileManager.default.copyItem(at: source, to: file)
        return MacDownloadedFile(folder: folder, file: file, handle: MediaFileHandle(noHandle: .init()))
    }

    func testSharedStandardPreparationScalesVideoDropsMetadataAndWritesThumbnailAndBlurhash() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let originalMetadata = try await AVURLAsset(url: source).load(.commonMetadata)
        XCTAssertFalse(originalMetadata.isEmpty)
        let video = try await MacPreparedVideo.prepare(source, highQuality: false, maxUploadSize: nil, under: root.appendingPathComponent("Uploads"))
        XCTAssertEqual(video.file.pathExtension, "mp4")
        XCTAssertNotEqual(video.file, source)
        XCTAssertLessThanOrEqual(max(video.info.width ?? 0, video.info.height ?? 0), 1280)
        XCTAssertGreaterThan(video.info.duration ?? 0, 0)
        XCTAssertEqual(video.info.mimetype, "video/mp4")
        let metadata = try await AVURLAsset(url: video.file).load(.commonMetadata)
        XCTAssertFalse(metadata.contains { $0.identifier == .commonIdentifierDescription })
        XCTAssertNotNil(video.info.blurhash)
        XCTAssertNotNil(Blurhash.image(try XCTUnwrap(video.info.blurhash)))
        let thumbnail = try XCTUnwrap(CGImageSourceCreateWithURL(video.thumbnail as CFURL, nil))
        let frame = try XCTUnwrap(CGImageSourceCreateImageAtIndex(thumbnail, 0, nil))
        XCTAssertLessThanOrEqual(max(frame.width, frame.height), 800)
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: video.folder.path)[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        video.remove()
        XCTAssertFalse(FileManager.default.fileExists(atPath: video.folder.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    func testHighQualityPreservesOriginalBytesAndLimitOrInvalidMediaLeavesNoCopies() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let uploads = root.appendingPathComponent("Uploads")
        let video = try await MacPreparedVideo.prepare(source, highQuality: true, maxUploadSize: nil, under: uploads)
        XCTAssertEqual(try Data(contentsOf: video.file), try Data(contentsOf: source))
        XCTAssertEqual(video.info.width, 1600)
        XCTAssertEqual(video.info.height, 900)
        let params = MacPreparedAttachment.video(video).parameters(caption: "  Video caption  ", reply: "$original", description: "Image-only description")
        XCTAssertEqual(params.caption, "Video caption")
        XCTAssertEqual(params.inReplyTo, "$original")
        XCTAssertNil(params.extraContentJson)
        XCTAssertNil(MacPreparedAttachment.video(video).parameters(caption: "  ", reply: nil, description: "").caption)
        video.remove()
        do {
            _ = try await MacPreparedVideo.prepare(source, highQuality: true, maxUploadSize: 1, under: uploads)
            XCTFail("Must enforce the homeserver limit")
        } catch MacTransferFiles.Failure.tooLarge(1) {}
        let invalid = root.appendingPathComponent("invalid.mov")
        try Data([1, 2, 3]).write(to: invalid)
        do { _ = try await MacPreparedVideo.prepare(invalid, highQuality: false, maxUploadSize: nil, under: uploads); XCTFail("Invalid movie") }
        catch {}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: uploads.path), [])
    }

    func testVideoPayloadKeepsCaptionFilenameLinksAndDeletedRowsLoseVideo() throws {
        let video = try content(caption: "Read this")
        let payload = MacMessagePayload(type: .video(content: video), fallback: "video", eventJson: nil)
        XCTAssertEqual(payload.body, "Read this")
        XCTAssertEqual(payload.video?.content.filename, "urgent.mov")
        XCTAssertTrue(payload.safetyText.contains("urgent.mov"))
        XCTAssertTrue(payload.safetyText.contains("https://example.invalid/payment"))
        XCTAssertNotNil(payload.warning(including: nil, isOwn: false, eventID: "$video"))
        XCTAssertNil(MacMessagePayload(kind: .redacted, eventJson: nil)?.video)
        XCTAssertNil(MacMessagePayload(kind: .unableToDecrypt, eventJson: nil)?.video)
        XCTAssertEqual(MacAttachmentKind.of(URL(fileURLWithPath: "/video.MOV")), .video)
        XCTAssertEqual(MacAttachmentKind.of(URL(fileURLWithPath: "/video.mp4")), .video)
        XCTAssertEqual(MacAttachmentKind.of(URL(fileURLWithPath: "/file.pdf")), .file)
    }

    @MainActor
    func testVideoCaptionEditsKeepMediaKindAndEmptyReplyPreviewNamesVideo() throws {
        var message = MacMessage(id: "video", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "", richText: BipeMessageText(body: ""), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: true, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$video", canReply: true, canReact: true, reactions: [])
        message.video = MacVideo(content: try content())
        XCTAssertEqual(message.composerPreview, BipeStrings.appMediaVideo)
        guard case .mediaCaption(let caption, _, _) = try message.editedContent("") else { return XCTFail("Media caption edit required") }
        XCTAssertEqual(caption, "")
        XCTAssertNotNil(message.video?.durationLabel)
        XCTAssertLessThanOrEqual(message.video?.previewSize.width ?? 0, 260)
    }

    @MainActor
    func testMoviePickerRoutesToVideoAndCancelPreservesComposerDraftAndSource() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let model = MacTimeline(drafts: MacDrafts())
        model.draft = "Keep the draft"
        await model.preparePickedURL(source, maxUploadSize: nil)
        let prepared = try XCTUnwrap(model.videoAttachment)
        XCTAssertNil(model.fileAttachment)
        XCTAssertNil(model.attachment)
        XCTAssertFalse(model.preparing)
        model.cancelAttachment()
        XCTAssertNil(model.preparedAttachment)
        XCTAssertEqual(model.draft, "Keep the draft")
        XCTAssertFalse(FileManager.default.fileExists(atPath: prepared.folder.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    @MainActor
    func testHiddenMediaDoesNotFetchPosterAndALateThumbnailCannotReappearAfterHiding() async throws {
        let media = MacMedia()
        XCTAssertFalse(media.showsAutomatically(isOwn: false, isPrivate: true, fromRequest: false))
        media.setPolicy(.on)
        XCTAssertFalse(media.showsAutomatically(isOwn: false, isPrivate: true, fromRequest: true))
        XCTAssertTrue(media.showsAutomatically(isOwn: true, isPrivate: false, fromRequest: true))
        let poster = MacVideoPoster()
        var calls = 0
        await poster.load(shows: false) { calls += 1; return nil }
        XCTAssertEqual(calls, 0)
        let started = expectation(description: "poster request")
        var continuation: CheckedContinuation<NSImage?, Never>?
        let load = Task {
            await poster.load(shows: true) {
                await withCheckedContinuation { continuation = $0; started.fulfill() }
            }
        }
        await fulfillment(of: [started], timeout: 3)
        await poster.load(shows: false) { XCTFail("Hidden media fetched"); return nil }
        continuation?.resume(returning: NSImage(size: CGSize(width: 10, height: 10)))
        await load.value
        guard case .idle = poster.phase else { return XCTFail("Late poster escaped the privacy gate") }
    }

    @MainActor
    func testCloseAndAccountResetReleasePlayerAndDecryptedFile() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let media = MacMedia()
        let model = MacVideoPlayback()
        for resetAccount in [false, true] {
            let file = try lease(source, under: root)
            await model.load(media: media) { file }
            guard case .ready(let session) = model.phase else { return XCTFail("Playable movie did not load") }
            XCTAssertNotNil(session.player.currentItem)
            XCTAssertFalse(session.player.isMuted)
            if resetAccount { media.reset() } else { model.close() }
            XCTAssertNil(session.player.currentItem)
            XCTAssertEqual(session.player.rate, 0)
            XCTAssertFalse(FileManager.default.fileExists(atPath: file.folder.path))
            guard case .idle = model.phase else { return XCTFail("Player stayed open") }
        }
    }

    @MainActor
    func testLateDownloadAfterCloseAndUnplayableMediaRemoveTheirLeases() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("broken.mov")
        try Data([1, 2, 3]).write(to: source)
        let model = MacVideoPlayback()
        let file = try lease(source, under: root)
        await model.load { file }
        guard case .failed = model.phase else { return XCTFail("Unplayable movie must fail visibly") }
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.folder.path))
        let late = try lease(source, under: root)
        let started = expectation(description: "download")
        var continuation: CheckedContinuation<MacDownloadedFile, Never>?
        let load = Task { await model.load { await withCheckedContinuation { continuation = $0; started.fulfill() } } }
        await fulfillment(of: [started], timeout: 3)
        model.close()
        continuation?.resume(returning: late)
        await load.value
        XCTAssertFalse(FileManager.default.fileExists(atPath: late.folder.path))
        guard case .idle = model.phase else { return XCTFail("A closed viewer reopened") }
    }

    func testLaunchCleanupRemovesAbandonedVideoCopiesAndKeepsUnrelatedFiles() throws {
        let caches = try folder(); defer { try? FileManager.default.removeItem(at: caches) }
        let app = caches.appendingPathComponent("chat.bipe.app.mac")
        let abandoned = app.appendingPathComponent("Uploads/abandoned/video.mp4")
        try FileManager.default.createDirectory(at: abandoned.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([1]).write(to: abandoned)
        let unrelated = app.appendingPathComponent("keep.txt")
        try Data([2]).write(to: unrelated)
        MacLocalCache.clean(caches: caches)
        XCTAssertFalse(FileManager.default.fileExists(atPath: abandoned.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    @MainActor
    func testCancelledVideoPreparationCannotOpenTheAttachmentSheet() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let model = MacTimeline(drafts: MacDrafts())
        let preparing = Task { await model.prepareVideo(source, maxUploadSize: nil) }
        for _ in 0..<250 {
            if model.preparing { break }
            await Task.yield()
        }
        guard model.preparing else { await preparing.value; return XCTFail("Preparation never entered its active state") }
        model.cancelAttachment()
        await preparing.value
        XCTAssertNil(model.preparedAttachment)
        XCTAssertNil(model.uploadError)
        XCTAssertFalse(model.preparing)
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    @MainActor
    func testVideoComposerRendersWithTheSharedCaptionAndSendSurface() async throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let source = try await movie(in: root)
        let video = try await MacPreparedVideo.prepare(source, highQuality: true, maxUploadSize: nil, under: root.appendingPathComponent("Uploads"))
        defer { video.remove() }
        let model = MacTimeline(drafts: MacDrafts())
        let view = NSHostingView(rootView: MacVideoComposer(video: video, model: model, maxUploadSize: { nil }).macFont(.body))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 600, height: 500), styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view
        defer { window.close() }
        window.orderFront(nil)
        try await Task.sleep(for: .milliseconds(150))
        view.layoutSubtreeIfNeeded()
        let size = view.fittingSize
        XCTAssertEqual(size.width, 560, accuracy: 2)
        XCTAssertLessThan(size.height, 620)
        window.setContentSize(size)
        try await Task.sleep(for: .milliseconds(150))
        view.layoutSubtreeIfNeeded()
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        var colors: Set<String> = []
        for y in stride(from: 0, to: bitmap.pixelsHigh, by: 5) {
            for x in stride(from: 0, to: bitmap.pixelsWide, by: 5) {
                if let color = bitmap.colorAt(x: x, y: y), color.alphaComponent > 0.9 { colors.insert(color.description) }
            }
        }
        XCTAssertGreaterThan(colors.count, 8, "A blank capture is not visual evidence")
        let capture = XCTAttachment(data: try XCTUnwrap(bitmap.representation(using: .png, properties: [:])), uniformTypeIdentifier: "public.png")
        capture.name = "mac-video-composer"
        capture.lifetime = .keepAlways
        add(capture)
    }
}
