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

    @Test fun accountCaseAndRoomIdentityArePreserved() {
        val first = requireNotNull(BipeNotificationTarget.create("@owner:example.invalid", "!shared:example.invalid"))
        assertNotEquals(first, BipeNotificationTarget.create("@Owner:example.invalid", first.roomId))
        assertNotEquals(first, BipeNotificationTarget.create(first.accountUserId, "!different:example.invalid"))
        assertEquals(first, BipeNotificationTarget.create(first.accountUserId, first.roomId))
    }
}
