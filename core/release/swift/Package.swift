// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// bipe-core from its GitHub release (bipe-chat/bipe#1277 item 4). `version` and `checksum` are the values of
// ../release.json; Sources/BipeCore/generated/bipe_core.swift is the release's Swift bindings, byte for byte.
// tools/core-release.test.mjs checks all three. A client adds this package and the product BipeCore.
import PackageDescription

let version = "0.1.15"
let checksum = "fbc13f336fd161a02e98a31b20f4d417d8ec9e0459043d7d4ad0811e940d59be"

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
