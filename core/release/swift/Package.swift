// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// bipe-core from its GitHub release (bipe-chat/bipe#1277 item 4). `version` and `checksum` are the values of
// ../release.json; Sources/BipeCore/generated/bipe_core.swift is the release's Swift bindings, byte for byte.
// tools/core-release.test.mjs checks all three. A client adds this package and the product BipeCore.
import PackageDescription

let version = "0.1.11"
let checksum = "7eaad6373da3f7b617791906ee02bd6d77f0a826ae0238bbc8a794ae30ff751d"

let package = Package(
    name: "BipeCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "BipeCore", targets: ["BipeCore"])],
    targets: [
        .binaryTarget(
            name: "bipe_coreFFI",
            url: "https://github.com/bipe-chat/bipe-app/releases/download/core-\(version)/bipe_coreFFI-\(version).xcframework.zip",
            checksum: checksum
        ),
        .target(name: "BipeCore", dependencies: ["bipe_coreFFI"], swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(
            name: "BipeCoreReleaseTests",
            dependencies: ["BipeCore"],
            resources: [.copy("badges.json")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
