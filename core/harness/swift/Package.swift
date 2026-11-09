// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import PackageDescription

// Loads the packaged XCFramework next to the Matrix SDK's XCFramework (bipe-chat/bipe#1172).
// core.yml writes build/ and Sources/BipeCore/generated before it runs this package.
let package = Package(
    name: "BipeCoreHarness",
    platforms: [.iOS(.v17), .macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/matrix-org/matrix-rust-components-swift", exact: "26.08.11"),
    ],
    targets: [
        .binaryTarget(name: "bipe_coreFFI", path: "build/bipe_coreFFI.xcframework"),
        .target(name: "BipeCore", dependencies: ["bipe_coreFFI"], swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(
            name: "BipeCoreHarnessTests",
            dependencies: ["BipeCore", .product(name: "MatrixRustSDK", package: "matrix-rust-components-swift")],
            resources: [.copy("badges.json"), .copy("badge-issuers.json"), .copy("theft-marks.json"), .copy("ed25519-cases.json")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
