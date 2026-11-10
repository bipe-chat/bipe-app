// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Security rule 8 (bipe#595). Ports of `sealLookalike()` and `contactCollision()` in bipe-shared tools/lib/registries.mjs.
public nonisolated enum BipeNameLookalikes {
    /// The first `BipeRegistries.sealLookalikeEmoji` entry in `name`, or `nil` (bipe-core `sealLookalike`).
    public static func sealLookalike(in name: String) -> String? {
        BipeCore.sealLookalike(name: name)
    }

    /// The contact name that `name` copies after normalising with the homoglyph map, or `nil` (bipe-core
    /// `contactCollision`). An empty name never collides.
    public static func contactCollision(name: String, contactNames: [String]) -> String? {
        BipeCore.contactCollision(name: name, contactNames: contactNames)
    }
}
