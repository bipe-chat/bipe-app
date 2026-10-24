// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// Same cases as bipe-shared tools/name-lookalikes.test.mjs.
struct NameLookalikesTests {
    @Test
    func sealLookalikeWithOrWithoutVariationSelector() {
        #expect(BipeNameLookalikes.sealLookalike(in: "Ana Souza ✅") == "✅")
        #expect(BipeNameLookalikes.sealLookalike(in: "Ana ✔\u{FE0F}") == "✔")
        #expect(BipeNameLookalikes.sealLookalike(in: "💜 Banco") == "💜")
        #expect(BipeNameLookalikes.sealLookalike(in: "Ana Souza") == nil)
        #expect(BipeNameLookalikes.sealLookalike(in: "Ana 😀") == nil)
    }

    @Test
    func contactCollisionAfterFolding() {
        let contacts = ["Maria Souza", "Mãe"]
        #expect(BipeNameLookalikes.contactCollision(name: "maria  souza", contactNames: contacts) == "Maria Souza")
        #expect(BipeNameLookalikes.contactCollision(name: "M\u{0430}ria S\u{043E}uza", contactNames: contacts) == "Maria Souza")
        #expect(BipeNameLookalikes.contactCollision(name: "mae", contactNames: contacts) == "Mãe")
        #expect(BipeNameLookalikes.contactCollision(name: "Maria Silva", contactNames: contacts) == nil)
        #expect(BipeNameLookalikes.contactCollision(name: "   ", contactNames: contacts) == nil)
    }
}
