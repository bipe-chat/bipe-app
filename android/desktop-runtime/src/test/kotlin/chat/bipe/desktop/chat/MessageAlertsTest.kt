// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.junit.Assert.*
import org.junit.Test

class MessageAlertsTest {
    private fun row(id: String, incoming: Long?, unread: Long = 1, mentions: Long = 0, preview: String? = "Oi") =
        ChatRow(id, "Ana", null, emptyList(), true, false, false, unread, preview, incoming, latestIncoming = incoming, mentions = mentions)

    @Test fun historyAtLaunchIsSilentAndANewIncomingMessageAlertsOnce() {
        val alerts = MessageAlerts(startedMs = 1_000)
        assertTrue(alerts.update(listOf(row("a", 900)), { false }).alerts.isEmpty())
        val first = alerts.update(listOf(row("a", 1_100)), { false })
        assertEquals(listOf("a"), first.alerts.map { it.row.id })
        assertTrue(alerts.update(listOf(row("a", 1_100)), { false }).alerts.isEmpty())
    }

    @Test fun theOpenChatGetsNoAlertAndOpeningOrReadingClearsIt() {
        val alerts = MessageAlerts(startedMs = 0)
        assertTrue(alerts.update(listOf(row("a", 10)), { it == "a" }).alerts.isEmpty())
        alerts.update(listOf(row("b", 10)), { false })
        assertEquals(setOf("b"), alerts.update(listOf(row("b", 10)), { it == "b" }).cleared)
        alerts.update(listOf(row("c", 20)), { false })
        assertEquals(setOf("c"), alerts.update(listOf(row("c", 20, unread = 0)), { false }).cleared)
    }

    @Test fun quietRoomsAlertOnlyOnANewMention() {
        val alerts = MessageAlerts(startedMs = 0)
        val plain = alerts.update(listOf(row("g", 10)), { false }).alerts.single()
        assertFalse(MessageAlerts.alerts(NotifyMode.QUIET, plain.newMention))
        val mention = alerts.update(listOf(row("g", 20, mentions = 1)), { false }).alerts.single()
        assertTrue(MessageAlerts.alerts(NotifyMode.QUIET, mention.newMention))
        assertFalse(MessageAlerts.alerts(NotifyMode.MUTE, true))
        assertTrue(MessageAlerts.alerts(NotifyMode.ALL, false))
    }

    @Test fun undecryptableOrMissingPreviewUsesTheGenericText() {
        val labels = ChatLabels("Room", "Person", "Locked", "Unsupported")
        assertEquals("Ana" to "Oi", MessageAlerts.content(row("a", 1), labels, "Bipe", "New message"))
        assertEquals("Bipe" to "New message", MessageAlerts.content(row("a", 1, preview = "Locked"), labels, "Bipe", "New message"))
        assertEquals("Bipe" to "New message", MessageAlerts.content(row("a", 1, preview = null), labels, "Bipe", "New message"))
    }
}
