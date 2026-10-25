// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** bipe-chat/bipe#229: the profile adapter keeps identity proof, personal confirmation and changes apart. */
class BipeTrustSourcesTest {
    private val ana = "@ana:example.test"
    private fun identity(verified: Boolean, violation: Boolean = false, key: String? = "MSK") =
        BipeTrustSources.Identity(key, verified, violation)

    @Test fun unknownIdentityVerifiesNothing() {
        for (id in listOf(null, identity(true, key = null), identity(true, key = ""))) {
            val facts = BipeTrustSources.profileFacts(ana, id, BipeTrustSources.Seal.Verified("Padaria Ana"))
            assertEquals("unverified", facts.identity)
            assertNull(facts.verifiedName)
            assertEquals("unconfirmed", facts.contact)
            assertEquals("unknown", facts.change)
        }
    }

    @Test fun sealAndConfirmationAreSeparateFacts() {
        val sealOnly = BipeTrustSources.profileFacts(ana, identity(false), BipeTrustSources.Seal.Verified("Padaria Ana"))
        assertEquals("verified", sealOnly.identity)
        assertEquals("Padaria Ana", sealOnly.verifiedName)
        assertEquals("a seal never confirms the person", "unconfirmed", sealOnly.contact)
        assertEquals("none", sealOnly.change)

        val confirmedOnly = BipeTrustSources.profileFacts(ana, identity(true), BipeTrustSources.Seal.None)
        assertEquals("unverified", confirmedOnly.identity)
        assertEquals("confirmed", confirmedOnly.contact)

        val both = BipeTrustSources.profileFacts(ana, identity(true), BipeTrustSources.Seal.Verified("Padaria Ana"))
        assertEquals("verified", both.identity)
        assertEquals("confirmed", both.contact)
        assertNull("no warning is no safety assertion", both.warningKind)
    }

    @Test fun identityChangeClearsConfirmationAndShows() {
        for (verified in listOf(false, true)) {
            val facts = BipeTrustSources.profileFacts(ana, identity(verified, violation = true), BipeTrustSources.Seal.None)
            assertEquals("unconfirmed", facts.contact)
            assertEquals("identity", facts.change)
        }
    }

    @Test fun suspendedSealIsNotVerified() {
        val facts = BipeTrustSources.profileFacts(ana, identity(false), BipeTrustSources.Seal.Suspended)
        assertEquals("suspended", facts.identity)
        assertNull(facts.verifiedName)
    }
}
