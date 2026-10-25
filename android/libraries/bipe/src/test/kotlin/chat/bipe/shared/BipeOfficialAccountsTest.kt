// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BipeOfficialAccountsTest {
    @Test fun onlyRegistryUserIdsAreOfficial() {
        BipeRegistries.officialAccountIds.forEach { assertTrue(it, BipeOfficialAccounts.isOfficial(it)) }
        listOf(
            "@suporte:example.com", "@Suporte:bipe.net.br", "suporte", "Suporte Bipe", "Novidades do Bipe",
            "@novidades:bipe.net.br", " @suporte:bipe.net.br", "@ana:bipe.net.br", "",
        ).forEach { assertFalse(it, BipeOfficialAccounts.isOfficial(it)) }
        assertFalse(BipeOfficialAccounts.isOfficial(null))
    }
}
