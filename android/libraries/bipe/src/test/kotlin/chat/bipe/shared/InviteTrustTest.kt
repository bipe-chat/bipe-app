// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.invites.BipeInviteTrust
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTrustTest {
    @Test fun trustAppliesOnlyToTheNamedPairAndNeverOverridesBlocking() {
        val pair = "@inviter:example.invalid"
        val pairs = setOf(pair)
        assertTrue(BipeInviteTrust.acceptsAutomatically(true, pair, false, pairs))
        assertFalse(BipeInviteTrust.acceptsAutomatically(false, pair, false, pairs))
        assertFalse(BipeInviteTrust.acceptsAutomatically(true, pair, true, pairs))
        assertFalse(BipeInviteTrust.acceptsAutomatically(true, "@other:example.invalid", false, pairs))
        assertFalse(BipeInviteTrust.acceptsAutomatically(true, pair, false, emptySet()))
        assertFalse(BipeInviteTrust.acceptsAutomatically(true, "", false, setOf("")))
    }
}
