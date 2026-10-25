// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// ADR 0117: the Bipe badge needs the exact user ID from registries/official-accounts.json.
struct OfficialAccountsTests {
    @Test
    func registryUserIdsAreOfficial() {
        #expect(!BipeRegistries.officialAccountIDs.isEmpty)
        for userId in BipeRegistries.officialAccountIDs {
            #expect(BipeOfficialAccounts.isOfficial(userId))
        }
    }

    @Test
    func namesHandlesAndLookalikesAreNot() {
        for userId in ["@suporte:example.com", "@Suporte:bipe.net.br", "suporte", "Suporte Bipe", "Novidades do Bipe",
                       "@novidades:bipe.net.br", " @suporte:bipe.net.br", "@ana:bipe.net.br", ""] {
            #expect(!BipeOfficialAccounts.isOfficial(userId))
        }
        #expect(!BipeOfficialAccounts.isOfficial(nil))
    }
}
