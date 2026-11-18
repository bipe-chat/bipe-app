// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DmReceiptsTest {
    private val policy = DmReceiptPolicy("own", true, true)
    private fun row(id: String, own: Boolean = true, readers: Set<String> = emptySet()) = MessageRow(
        id, "Person", own, "body", MessageRow.Kind.TEXT, 1, MessageRow.Delivery.SENT, readersAtEvent = readers)
    private fun message(row: MessageRow) = TimelineSnapshot.Message(row)

    @Test fun readOnANewerIncomingOrHiddenEventCoversOlderOwnMessagesAndIgnoresOwnReceipts() {
        val rows = listOf(message(row("old")), message(row("new")), message(row("incoming", false, setOf("peer", "own"))))
        val receipts = rows.dmReceipts(policy)
        assertEquals(DmReceipt(DmReceipt.Tick.READ, false), receipts["old"])
        assertEquals(DmReceipt(DmReceipt.Tick.READ, true), receipts["new"])
        assertNull(receipts["incoming"])
        assertEquals(DmReceipt.Tick.SENT, listOf(message(row("old", readers = setOf("own")))).dmReceipts(policy)["old"]!!.tick)
        assertEquals(DmReceipt.Tick.READ, listOf(message(row("old")), TimelineSnapshot.ReadPoint(setOf("peer"))).dmReceipts(policy)["old"]!!.tick)
    }

    @Test fun privacyIsReciprocalGroupsStayOutAndLocalFailuresOrDeletedRowsNeverBecomeRead() {
        val rows = listOf(message(row("old")), message(row("queued").copy(delivery = MessageRow.Delivery.SENDING)),
            message(row("failed").copy(delivery = MessageRow.Delivery.FAILED)),
            message(row("deleted").copy(kind = MessageRow.Kind.DELETED)), TimelineSnapshot.ReadPoint(setOf("peer")))
        val receipts = rows.dmReceipts(policy.copy(showsRead = false))
        assertEquals(DmReceipt.Tick.DELIVERED, receipts["old"]!!.tick)
        assertEquals(DmReceipt.Tick.SENDING, receipts["queued"]!!.tick)
        assertEquals(DmReceipt.Tick.FAILED, receipts["failed"]!!.tick)
        assertNull(receipts["deleted"])
        assertTrue(rows.dmReceipts(policy.copy(isDm = false)).isEmpty())
    }

    @Test fun staleCacheCannotOverrideFreshOffAndOnlyAnObservedSyncChangeCanEnableItAgain() = runBlocking {
        var cache: String? = """{"readReceipts":true}"""
        val preference = ReceiptPreference({ """{"readReceipts":false}""" }, { cache })
        assertFalse(preference.refresh()); assertFalse(preference.refresh())
        cache = """{"readReceipts":false}"""; assertFalse(preference.refresh())
        cache = """{"readReceipts":true}"""; assertTrue(preference.refresh())
    }

    @Test fun offWinsFirstSyncAndAnUnavailableOrMalformedFirstReadNeverValidatesPublicReadState() = runBlocking {
        var cache: String? = """{"readReceipts":false}"""
        val preference = ReceiptPreference({ """{"readReceipts":true}""" }, { cache })
        assertFalse(preference.refresh())
        cache = """{"readReceipts":true}"""; assertTrue(preference.refresh())
        var fresh: String? = """{"readReceipts":"false"}"""
        val invalid = ReceiptPreference({ fresh }, { null })
        assertTrue(runCatching { invalid.refresh() }.isFailure)
        fresh = """{"readReceipts":false}"""; assertFalse(invalid.refresh())
        assertTrue(runCatching { ReceiptPreference({ error("Offline") }, { null }).refresh() }.isFailure)
    }
}
