// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Security rule 8 (bipe#595). Ports of `sealLookalike()` and `contactCollision()` in bipe-shared tools/lib/registries.mjs.
public nonisolated enum BipeNameLookalikes {
    /// The first `BipeRegistries.sealLookalikeEmoji` entry in `name`, or `nil`. Variation selectors do not count.
    public static func sealLookalike(in name: String) -> String? {
        let plain = withoutVariationSelectors(name)
        return BipeRegistries.sealLookalikeEmoji.first { entry in
            plain.range(of: withoutVariationSelectors(entry), options: .literal) != nil
        }
    }

    /// The contact name that `name` copies after normalising with the homoglyph map, or `nil`. An empty name never collides.
    public static func contactCollision(name: String, contactNames: [String]) -> String? {
        let normalized = NameNormalizer.normalize(name)
        guard !normalized.isEmpty else { return nil }
        return contactNames.first { NameNormalizer.normalize($0) == normalized }
    }

    private static func withoutVariationSelectors(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in text.unicodeScalars where scalar.value != 0xFE0E && scalar.value != 0xFE0F {
            scalars.append(scalar)
        }
        return String(scalars)
    }
}
