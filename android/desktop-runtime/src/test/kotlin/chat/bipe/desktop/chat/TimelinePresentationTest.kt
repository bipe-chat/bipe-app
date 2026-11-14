// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.VectorDiff
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TimelinePresentationTest {
    private fun message(id: String, own: Boolean = false, sender: String = "@peer:test") = MessageRow(
        id, "Same display name", own, "Body", MessageRow.Kind.TEXT, 1, MessageRow.Delivery.SENT, senderId = sender)

    @Test fun followingIncomingAndReadingHistoryAreSeparateUntilThePersonJumpsBack() {
        val policy = TimelineScrollPolicy()
        val first = message("first"); val next = message("next"); val latest = message("latest")
        assertTrue(policy.messagesChanged(listOf(first)))
        assertTrue(policy.messagesChanged(listOf(first, next)))
        policy.userScrolled(false)
        assertFalse(policy.messagesChanged(listOf(first, next, latest)))
        assertFalse(policy.messagesChanged(listOf(message("history"), first, next, latest)))
        assertFalse(policy.messagesChanged(listOf(first, next, latest.copy(isEdited = true))))
        policy.jumpToNewest()
        assertTrue(policy.messagesChanged(listOf(first, next, latest, message("new"))))
    }

    @Test fun ownArrivalFollowsButRemovingNewestCannotConfuseAnOlderOwnMessageWithANewSend() {
        val policy = TimelineScrollPolicy(); val own = message("own", own = true); val incoming = message("incoming")
        policy.messagesChanged(listOf(own, incoming)); policy.userScrolled(false)
        assertFalse(policy.messagesChanged(listOf(own)))
        val queued = message("queued", own = true).copy(delivery = MessageRow.Delivery.SENDING)
        assertTrue(policy.messagesChanged(listOf(own, queued)))
        assertFalse(policy.messagesChanged(listOf(own, queued.copy(delivery = MessageRow.Delivery.SENT))))
    }

    @Test fun roomViewportsDoNotResetWhileLoadingAndTheirFollowPoliciesAreIndependent() {
        val first = TimelineScrollPolicy(); val other = TimelineScrollPolicy()
        val row = message("same-id")
        first.messagesChanged(listOf(row)); first.userScrolled(false)
        assertFalse(first.messagesChanged(emptyList()))
        assertFalse(first.messagesChanged(listOf(row)))
        assertTrue(other.messagesChanged(listOf(row)))
        assertFalse(first.messagesChanged(listOf(row, message("new"))))
        first.userScrolled(true)
        assertTrue(first.messagesChanged(listOf(row, message("newer"))))
    }

    @Test fun sdkDividersSurviveHiddenItemsHistoryPrependAndDividerReplacementWithoutChangingMessageIdentity() {
        val vector = SnapshotVector<TimelineSnapshot>()
        vector.apply(VectorDiff.Reset(listOf(TimelineSnapshot.Day(200), null, TimelineSnapshot.Message(message("b")),
            TimelineSnapshot.Message(message("c")))))
        assertEquals(listOf(200L, null), vector.visible().messageRows().map { it.dayBefore })
        vector.apply(VectorDiff.Insert(0, TimelineSnapshot.Day(100)))
        vector.apply(VectorDiff.Insert(1, TimelineSnapshot.Message(message("a"))))
        var rows = vector.visible().messageRows()
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(listOf(100L, 200L, null), rows.map { it.dayBefore })
        vector.apply(VectorDiff.Set(2, TimelineSnapshot.Day(250)))
        vector.apply(VectorDiff.Remove(4))
        rows = vector.visible().messageRows()
        assertEquals(listOf("a", "c"), rows.map { it.id })
        assertEquals(listOf(100L, 250L), rows.map { it.dayBefore })
    }

    @Test fun groupNamesNeverCollapseAndOnlyTheCurrentDmPeerCanLoseTheSenderLine() {
        val peer = message("peer")
        assertFalse(peer.showsSender(true, "@peer:test"))
        assertTrue(peer.showsSender(false, "@peer:test"))
        assertTrue(peer.showsSender(true, null))
        assertTrue(message("former", sender = "@former:test").showsSender(true, "@peer:test"))
        assertTrue(peer.copy(kind = MessageRow.Kind.UNDECRYPTABLE).showsSender(true, "@peer:test"))
        assertFalse(peer.copy(own = true).showsSender(false, null))
        assertTrue(peer.copy(kind = MessageRow.Kind.DELETED).showsSender(false, null))
    }

    @Test fun todayAndYesterdayUseLocalDaysAcrossDstAndYearBoundaries() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val day = LocalDate.of(2026, 10, 26)
        val timestamp = ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Yesterday", TimelineDates.label(timestamp, day, zone, Locale.ENGLISH, "Today", "Yesterday"))
        assertEquals("Today", TimelineDates.label(timestamp, day.minusDays(1), zone, Locale.ENGLISH, "Today", "Yesterday"))
        val newYear = LocalDate.of(2027, 1, 1)
        assertEquals("Yesterday", TimelineDates.label(newYear.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            newYear, zone, Locale.ENGLISH, "Today", "Yesterday"))
    }

    @Test fun olderDatesUseAppLanguageAndOnlyAnotherYearShowsItsYearAndFullTimestampStaysAccessible() {
        val zone = ZoneId.of("America/Sao_Paulo"); val day = LocalDate.of(2026, 6, 3)
        val timestamp = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 11, 14)
        val english = TimelineDates.label(timestamp, today, zone, Locale.ENGLISH, "Today", "Yesterday")
        val portuguese = TimelineDates.label(timestamp, today, zone, Locale.forLanguageTag("pt-BR"), "Hoje", "Ontem")
        assertTrue(english.contains("June")); assertFalse(english.contains("2026"))
        assertTrue(portuguese.contains("junho")); assertFalse(portuguese.contains("2026"))
        assertTrue(TimelineDates.label(timestamp, today.plusYears(1), zone, Locale.ENGLISH, "Today", "Yesterday").contains("2026"))
        assertTrue(TimelineDates.fullTimestamp(timestamp, zone, Locale.ENGLISH).contains("2026"))
    }
}
