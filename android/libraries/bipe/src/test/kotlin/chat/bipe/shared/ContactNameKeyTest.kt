// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContactNameKeyTest {
    @Test fun answersCannotCrossAccountsPeersOrIdentityChanges() {
        fun key(account: String = "@owner:example.invalid", peer: String = "@peer:example.invalid", saved: String = "Mãe", name: String = "Maria Souza") =
            BipeContactNameKey.make(account, peer, saved, name)
        assertEquals(key(), key(saved = "  MÃE  ", name = "Mária  Souza"))
        assertNotEquals(key(), key(account = "@other:example.invalid"))
        assertNotEquals(key(), key(peer = "@other:example.invalid"))
        assertNotEquals(key(), key(saved = "Nina"))
        assertNotEquals(key(), key(name = "Joana Lima"))
        assertFalse(key().contains("Maria"))
        assertFalse(key().contains("example.invalid"))
        assertNotEquals(key("a", "bc"), key("ab", "c"))
    }
}
