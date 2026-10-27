// swift-tools-version: 6.2
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// BipeKit: Bipe logic for the iOS app. Sources/BipeKit/Generated and
// Sources/BipeKit/Resources come from bipe-shared tools/gen.mjs --app.

import PackageDescription

let package = Package(
    name: "BipeKit",
    defaultLocalization: "pt-BR",
    platforms: [
        .iOS(.v18),
        // macOS runs `swift test` on CI.
        .macOS(.v15),
    ],
    products: [
        .library(name: "BipeKit", targets: ["BipeKit"]),
    ],
    dependencies: [.package(url: "https://github.com/scinfu/SwiftSoup", exact: "2.14.3")],
    targets: [
        .target(
            name: "BipeKit",
            dependencies: [.product(name: "SwiftSoup", package: "SwiftSoup")],
            path: "Sources/BipeKit",
            resources: [.process("Resources")],
            swiftSettings: [
                .enableUpcomingFeature("NonisolatedNonsendingByDefault"),
                .enableUpcomingFeature("InferIsolatedConformances"),
            ]
        ),
        // Reads bipe-shared/test-vectors (ADR 0031 rule 4). BIPE_SHARED points at bipe-shared. Run: swift test
        .testTarget(
            name: "BipeKitTests",
            dependencies: ["BipeKit"],
            path: "Tests/BipeKitTests"
        ),
    ]
)
