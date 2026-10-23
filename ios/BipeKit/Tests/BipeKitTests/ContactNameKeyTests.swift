// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

struct ContactNameKeyTests {
    @Test func answersCannotCrossAccountsPeersOrIdentityChanges() {
        func key(_ account: String = "@owner:example.invalid", _ peer: String = "@peer:example.invalid", _ saved: String = "Mãe", _ name: String = "Maria Souza") -> String {
            BipeContactNameKey.make(accountId: account, peerId: peer, savedName: saved, verifiedName: name)
        }
        #expect(key() == key("@owner:example.invalid", "@peer:example.invalid", "  MÃE  ", "Mária  Souza"))
        #expect(key() != key("@other:example.invalid"))
        #expect(key() != key("@owner:example.invalid", "@other:example.invalid"))
        #expect(key() != key("@owner:example.invalid", "@peer:example.invalid", "Nina"))
        #expect(key() != key("@owner:example.invalid", "@peer:example.invalid", "Mãe", "Joana Lima"))
        #expect(!key().contains("Maria"))
        #expect(!key().contains("example.invalid"))
        #expect(key("a", "bc") != key("ab", "c"))
    }
}
