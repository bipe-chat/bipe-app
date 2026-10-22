// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// BipeKit: Bipe logic for the iOS app. Sources/BipeKit/Generated and
// Sources/BipeKit/Resources come from bipe-shared tools/gen.mjs --app.

import PackageDescription

let package = Package(
    name: "BipeKit",
    defaultLocalization: "pt-BR",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "BipeKit", targets: ["BipeKit"]),
    ],
    targets: [
        .target(
            name: "BipeKit",
            path: "Sources/BipeKit",
            resources: [.process("Resources")]
        ),
    ]
)
