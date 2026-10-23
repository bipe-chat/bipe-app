// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class NotificationTargetTest {
    @Test fun sharedRoomKeepsTwoAccountsSeparate() {
        val first = requireNotNull(BipeNotificationTarget.create("@owner:example.invalid", "!shared:example.invalid"))
        val other = requireNotNull(BipeNotificationTarget.create("@other:example.invalid", first.roomId))
        assertNotEquals(first, other)
        assertNotEquals(first.identifier, other.identifier)
        assertEquals("4ed58fedef0bf890aa305625182349782704c1457e912e921ea5be125678a36d", first.identifier)
        assertFalse(first.identifier.contains("owner"))
        assertFalse(first.identifier.contains("shared"))
        assertFalse(setOf(first).contains(other))
        assertEquals(listOf(other), listOf(first, other).filter { it != first })
    }

    @Test fun ownerlessAndMalformedLegacyRoutesCannotBecomeActions() {
        listOf(null, "", "owner", "@:example.invalid", "@owner:", " @owner:example.invalid", "@owner:example.invalid\n").forEach {
            assertNull(BipeNotificationTarget.create(it, "!shared:example.invalid"))
        }
        listOf(null, "", "shared", "!", "!shared room:example.invalid", "!shared\u0000:example.invalid").forEach {
            assertNull(BipeNotificationTarget.create("@owner:example.invalid", it))
        }
    }

    @Test fun registrationLifetimesSurviveRestartAndInvalidateOldActionsAtSignOut() {
        var saved = emptyMap<String, String>()
        fun store() = BipePushAccountRoutes({ saved }, { saved = it })
        val routes = store()
        val first = routes.routeFor("@owner:example.invalid")
        val other = routes.routeFor("@other:example.invalid")
        assertNotEquals(first, other)
        assertFalse(first.contains("owner"))
        assertEquals(first, store().routeFor("@owner:example.invalid"))
        assertEquals("@owner:example.invalid", store().accountFor(first))
        store().remove("@owner:example.invalid")
        assertNull(store().accountFor(first))
        assertEquals("@other:example.invalid", store().accountFor(other))
        assertNotEquals(first, store().routeFor("@owner:example.invalid"))
        assertNull(store().accountFor("legacy"))
        val ambiguous = UUID.randomUUID().toString()
        saved = mapOf("@owner:example.invalid" to ambiguous, "@other:example.invalid" to ambiguous)
        assertNull(store().accountFor(ambiguous))
    }

    @Test fun accountCaseAndRoomIdentityArePreserved() {
        val first = requireNotNull(BipeNotificationTarget.create("@owner:example.invalid", "!shared:example.invalid"))
        assertNotEquals(first, BipeNotificationTarget.create("@Owner:example.invalid", first.roomId))
        assertNotEquals(first, BipeNotificationTarget.create(first.accountUserId, "!different:example.invalid"))
        assertEquals(first, BipeNotificationTarget.create(first.accountUserId, first.roomId))
    }
}
