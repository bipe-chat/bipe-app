// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import ImageIO
import MatrixRustSDK
import SwiftUI
import UniformTypeIdentifiers
import XCTest
@testable import BipeMac

final class MacMediaTests: XCTestCase {
    func testFormattedLinksKeepTheirDestinationAndRejectExecutableSchemes() throws {
        let message = MacMessagePayload(body: "plain", formatted: FormattedBody(format: .html, body:
            "<b>Visit</b> <a href='https://example.invalid/path'>our page</a> <a href='javascript:alert(1)'>unsafe</a><img src='https://example.invalid/tracker'>"))
        XCTAssertEqual(message.richText.runs.compactMap(\.link), ["https://example.invalid/path"])
        XCTAssertTrue(message.richText.safetyText.contains("https://example.invalid/path"))
        XCTAssertFalse(message.richText.text.contains("tracker"))
        let rendered = MacRichText.attributed(message.richText)
        XCTAssertTrue(rendered.runs.contains { $0.link == URL(string: "https://example.invalid/path") })
        XCTAssertTrue(message.richText.runs.contains { $0.bold && $0.text == "Visit" })
    }

    func testPlainTextLinksAndImageCaptionsUseTheSameParser() throws {
        let plain = MacMessagePayload(body: "See https://example.invalid or mail@example.invalid")
        XCTAssertTrue(plain.richText.runs.contains { $0.link == "https://example.invalid" })
        XCTAssertTrue(plain.richText.runs.contains { $0.link == "mailto:mail@example.invalid" })
        let source = try MediaSource.fromUrl(url: "mxc://example.invalid/test")
        let content = ImageMessageContent(filename: "photo.jpg", caption: "Look here", formattedCaption:
            FormattedBody(format: .html, body: "<a href='https://example.invalid/photo'>Look here</a>"), source: source, info: nil)
        let payload = MacMessagePayload(type: .image(content: content), fallback: "photo.jpg", eventJson: nil)
        XCTAssertNotNil(payload.photo)
        XCTAssertEqual(payload.body, "Look here")
        XCTAssertEqual(payload.richText.runs.compactMap(\.link), ["https://example.invalid/photo"])
        let noCaption = MacMessagePayload(type: .image(content: ImageMessageContent(filename: "photo.jpg", caption: nil,
            formattedCaption: nil, source: source, info: nil)), fallback: "photo.jpg", eventJson: nil)
        XCTAssertEqual(noCaption.body, "")
    }

    func testPreparedPhotoAppliesOrientationAndStripsCameraAndLocationMetadata() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let input = folder.appendingPathComponent("synthetic.jpg")
        let context = try XCTUnwrap(CGContext(data: nil, width: 3000, height: 1200, bitsPerComponent: 8, bytesPerRow: 0,
                                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(context.makeImage())
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(input as CFURL, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 12.5, kCGImagePropertyGPSLongitude: 13.5],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "private synthetic camera note"],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFModel: "synthetic camera"],
        ] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let prepared = try MacImagePreparation.prepare(input)
        defer { prepared.remove() }
        XCTAssertEqual(prepared.info.height, 2048)
        XCTAssertLessThan(try XCTUnwrap(prepared.info.width), 2048)
        XCTAssertEqual(prepared.info.mimetype, "image/jpeg")
        for url in [prepared.file, prepared.thumbnail] {
            let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
            let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
            XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
            let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any]
            XCTAssertNil(exif?[kCGImagePropertyExifUserComment])
            let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
            XCTAssertNil(tiff?[kCGImagePropertyTIFFModel])
        }
        let high = try MacImagePreparation.prepare(input, highQuality: true)
        defer { high.remove() }
        XCTAssertEqual(high.info.width, 1200)
        XCTAssertEqual(high.info.height, 3000)
    }

    func testAnimatedImageKeepsFramesAndTiming() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let input = folder.appendingPathComponent("synthetic.gif")
        let context = try XCTUnwrap(CGContext(data: nil, width: 20, height: 16, bitsPerComponent: 8, bytesPerRow: 0,
                                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(input as CFURL, UTType.gif.identifier as CFString, 2, nil))
        for gray in [CGFloat(0.2), CGFloat(0.8)] {
            context.setFillColor(CGColor(gray: gray, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 20, height: 16))
            CGImageDestinationAddImage(destination, try XCTUnwrap(context.makeImage()), [
                kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.2],
            ] as CFDictionary)
        }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let prepared = try MacImagePreparation.prepare(input)
        defer { prepared.remove() }
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(prepared.file as CFURL, nil))
        XCTAssertEqual(CGImageSourceGetCount(source), 2)
        XCTAssertEqual(prepared.info.isAnimated, true)
        XCTAssertEqual(prepared.info.mimetype, "image/gif")
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        let timing = try XCTUnwrap(properties[kCGImagePropertyGIFDictionary] as? [CFString: Any])
        XCTAssertEqual(try XCTUnwrap(timing[kCGImagePropertyGIFDelayTime] as? Double), 0.2, accuracy: 0.01)
    }
}
