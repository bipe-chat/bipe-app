// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Phosphor icons (MIT, see LICENSE in this folder). tools/phosphor.mjs writes
// Sources/Phosphor from the @phosphor-icons/core package.

import PackageDescription

let package = Package(
    name: "Phosphor",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "Phosphor", targets: ["Phosphor"]),
    ],
    targets: [
        .target(
            name: "Phosphor",
            path: "Sources/Phosphor",
            resources: [.process("Resources")]
        ),
    ]
)
