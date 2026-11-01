// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import XCTest

// Store captures (bipe-chat/bipe#457): launches the Debug app once per slug and locale with BIPE_STORE_CAPTURE, the
// sample scene of ios/Bipe/StoreCapture. Writes the screenshot and the element tree in the bipe-web layout
// <out>/iphone69/light/<locale>/<slug>.png and <slug>.elements.json; the bipe-web callouts anchor on the element
// labels and frames. Input: TEST_RUNNER_STORE_CAPTURE_IN, the folder that tools/store-captures-ios.mjs wrote
// (<in>/<locale>/<slug>.json). Output: TEST_RUNNER_STORE_CAPTURE_OUT. Run on the 6.9-inch iPhone simulator
// (1320x2868 pixels); store-captures.yml does.
final class StoreCaptureTests: XCTestCase {
    private struct Element: Encodable {
        let frame: [Double]
        let id: String
        let label: String
        let type: UInt
    }

    private struct Elements: Encodable {
        let elements: [Element]
        let width: Double
        let height: Double
    }

    private static let languages = ["pt-BR": ("pt-BR", "pt_BR"), "en-US": ("en", "en_US")]

    func testStoreCaptures() throws {
        let env = ProcessInfo.processInfo.environment
        guard let input = env["STORE_CAPTURE_IN"], let output = env["STORE_CAPTURE_OUT"] else {
            throw XCTSkip("STORE_CAPTURE_IN and STORE_CAPTURE_OUT are not set")
        }
        let files = FileManager.default
        var count = 0
        for locale in try files.contentsOfDirectory(atPath: input).sorted() {
            guard let (language, region) = Self.languages[locale] else { continue }
            let folder = URL(fileURLWithPath: input).appendingPathComponent(locale)
            let out = URL(fileURLWithPath: output).appendingPathComponent("iphone69/light/\(locale)")
            try files.createDirectory(at: out, withIntermediateDirectories: true)
            for file in try files.contentsOfDirectory(atPath: folder.path).sorted() where file.hasSuffix(".json") {
                let slug = String(file.dropLast(5))
                try capture(slug: slug, json: folder.appendingPathComponent(file), language: language, region: region, out: out)
                count += 1
            }
        }
        XCTAssertGreaterThan(count, 0, "no capture input in \(input)")
    }

    private func capture(slug: String, json: URL, language: String, region: String, out: URL) throws {
        let app = XCUIApplication()
        app.launchEnvironment["BIPE_STORE_CAPTURE"] = json.path
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", region]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["store-capture-ready"].waitForExistence(timeout: 60), "\(slug): no scene")
        // Photos load from files and the seal sheet slides up: wait until the screen is still.
        Thread.sleep(forTimeInterval: 3)
        let screenshot = XCUIScreen.main.screenshot()
        try screenshot.pngRepresentation.write(to: out.appendingPathComponent("\(slug).png"))

        let snapshot = try app.snapshot()
        var elements: [Element] = []
        func walk(_ node: XCUIElementSnapshot) {
            let f = node.frame
            if f.width > 0, f.height > 0 {
                elements.append(Element(frame: [f.minX, f.minY, f.width, f.height], id: node.identifier, label: node.label,
                                        type: node.elementType.rawValue))
            }
            node.children.forEach(walk)
        }
        walk(snapshot)
        let screen = snapshot.frame
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(Elements(elements: elements, width: screen.width, height: screen.height))
            .write(to: out.appendingPathComponent("\(slug).elements.json"))
        app.terminate()
    }
}
