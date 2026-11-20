// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.VectorDiff
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Each case records what the policy or formatter answered at every step and compares the whole story once. */
class TimelinePresentationTest {
    private fun message(id: String, own: Boolean = false, sender: String = "@peer:test") = MessageRow(
        id, "Same display name", own, "Body", MessageRow.Kind.TEXT, 1, MessageRow.Delivery.SENT, senderId = sender)

    /** Feeds message lists and scroll events to one policy; returns whether each list moved the view to the newest. */
    private fun TimelineScrollPolicy.story(vararg steps: Any): List<Boolean> = steps.mapNotNull { step ->
        when (step) {
            is Scroll -> { userScrolled(step.atNewest); null }
            Jump -> { jumpToNewest(); null }
            is List<*> -> messagesChanged(step.filterIsInstance<MessageRow>())
            else -> error("Unknown step $step")
        }
    }
    private data class Scroll(val atNewest: Boolean)
    private data object Jump

    @Test fun dateJumpUsesLocalDayBoundariesThroughDaylightSavingChanges() {
        val spring = DayWindow(LocalDate.of(2026, 3, 29), ZoneId.of("Europe/Amsterdam"))
        val autumn = DayWindow(LocalDate.of(2026, 10, 25), ZoneId.of("Europe/Amsterdam"))
        val hour = 60L * 60 * 1_000
        assertEquals(23L * hour, spring.end - spring.start)
        assertEquals(25L * hour, autumn.end - autumn.start)
        assertEquals(DayJump.Empty, spring.target("later", spring.end, emptyList(), reachedStart = false))
    }

    @Test fun dateJumpUsesReadOnlyHistoryWhenLiveRowsCoverOnlyPartOfTheDay() {
        val day = DayWindow(LocalDate.of(2026, 11, 19), ZoneId.of("America/Sao_Paulo"))
        val later = message("later").copy(timestamp = day.start + 1_000, originalEventId = "\$later")
        assertEquals(DayJump.History("\$first", day.date, day.zone),
            day.target("\$first", day.start, listOf(later), reachedStart = false))
        val earlier = message("prior").copy(timestamp = day.start - 1)
        assertEquals(DayJump.Loaded("later"),
            day.target("\$first", day.start, listOf(earlier, later), reachedStart = false))
        assertEquals(DayJump.Loaded("later"),
            day.target("\$later", later.timestamp, listOf(later), reachedStart = false))
    }

    @Test fun followingIncomingAndReadingHistoryAreSeparateUntilThePersonJumpsBack() {
        val first = message("first"); val next = message("next"); val latest = message("latest")
        val answers = TimelineScrollPolicy().story(
            listOf(first), listOf(first, next), Scroll(false),
            listOf(first, next, latest), listOf(message("history"), first, next, latest),
            listOf(first, next, latest.copy(isEdited = true)), Jump,
            listOf(first, next, latest, message("new")))
        assertEquals(listOf(true, true, false, false, false, true), answers)
    }

    @Test fun ownArrivalFollowsButRemovingNewestCannotConfuseAnOlderOwnMessageWithANewSend() {
        val own = message("own", own = true); val incoming = message("incoming")
        val queued = message("queued", own = true).copy(delivery = MessageRow.Delivery.SENDING)
        val answers = TimelineScrollPolicy().story(
            listOf(own, incoming), Scroll(false), listOf(own), listOf(own, queued),
            listOf(own, queued.copy(delivery = MessageRow.Delivery.SENT)))
        assertEquals(listOf(false, true, false), answers.drop(1))
    }

    @Test fun roomViewportsDoNotResetWhileLoadingAndTheirFollowPoliciesAreIndependent() {
        val row = message("same-id")
        val first = TimelineScrollPolicy(); val other = TimelineScrollPolicy()
        // Interleaved on purpose: the second room's answer must not depend on the first room's scroll state.
        val loading = first.story(listOf(row), Scroll(false), emptyList<MessageRow>(), listOf(row)).drop(1)
        val otherRoom = other.story(listOf(row))
        val later = first.story(listOf(row, message("new")), Scroll(true), listOf(row, message("newer")))
        assertEquals(listOf(listOf(false, false), listOf(true), listOf(false, true)), listOf(loading, otherRoom, later))
    }

    @Test fun sdkDividersSurviveHiddenItemsHistoryPrependAndDividerReplacementWithoutChangingMessageIdentity() {
        val vector = SnapshotVector<TimelineSnapshot>()
        fun rows() = vector.visible().messageRows().map { it.id to it.dayBefore }
        vector.apply(VectorDiff.Reset(listOf(TimelineSnapshot.Day(200), null, TimelineSnapshot.Message(message("b")),
            TimelineSnapshot.Message(message("c")))))
        val reset = rows()
        vector.apply(VectorDiff.Insert(0, TimelineSnapshot.Day(100)))
        vector.apply(VectorDiff.Insert(1, TimelineSnapshot.Message(message("a"))))
        val prepended = rows()
        vector.apply(VectorDiff.Set(2, TimelineSnapshot.Day(250)))
        vector.apply(VectorDiff.Remove(4))
        assertEquals(listOf(
            listOf("b" to 200L, "c" to null),
            listOf("a" to 100L, "b" to 200L, "c" to null),
            listOf("a" to 100L, "c" to 250L),
        ), listOf(reset, prepended, rows()))
    }

    @Test fun groupNamesNeverCollapseAndOnlyTheCurrentDmPeerCanLoseTheSenderLine() {
        val peer = message("peer")
        val cases = mapOf(
            "dm peer" to peer.showsSender(true, "@peer:test"),
            "group" to peer.showsSender(false, "@peer:test"),
            "dm without known peer" to peer.showsSender(true, null),
            "former dm member" to message("former", sender = "@former:test").showsSender(true, "@peer:test"),
            "locked message from peer" to peer.copy(kind = MessageRow.Kind.UNDECRYPTABLE).showsSender(true, "@peer:test"),
            "own in group" to peer.copy(own = true).showsSender(false, null),
            "deleted in group" to peer.copy(kind = MessageRow.Kind.DELETED).showsSender(false, null),
        )
        assertEquals(mapOf("dm peer" to false, "group" to true, "dm without known peer" to true, "former dm member" to true,
            "locked message from peer" to true, "own in group" to false, "deleted in group" to true), cases)
    }

    @Test fun todayAndYesterdayUseLocalDaysAcrossDstAndYearBoundaries() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val day = LocalDate.of(2026, 10, 26)
        val newYear = LocalDate.of(2027, 1, 1)
        val timestamp = ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        fun label(at: Long, today: LocalDate) = TimelineDates.label(at, today, zone, Locale.ENGLISH, "Today", "Yesterday")
        assertEquals(listOf("Yesterday", "Today", "Yesterday"), listOf(
            label(timestamp, day), label(timestamp, day.minusDays(1)),
            label(newYear.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), newYear)))
    }

    @Test fun olderDatesUseAppLanguageAndOnlyAnotherYearShowsItsYearAndFullTimestampStaysAccessible() {
        val zone = ZoneId.of("America/Sao_Paulo"); val day = LocalDate.of(2026, 6, 3)
        val timestamp = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 11, 14)
        val english = TimelineDates.label(timestamp, today, zone, Locale.ENGLISH, "Today", "Yesterday")
        val portuguese = TimelineDates.label(timestamp, today, zone, Locale.forLanguageTag("pt-BR"), "Hoje", "Ontem")
        val nextYear = TimelineDates.label(timestamp, today.plusYears(1), zone, Locale.ENGLISH, "Today", "Yesterday")
        assertEquals(mapOf("english month" to true, "english year" to false, "portuguese month" to true, "portuguese year" to false,
            "another year shows it" to true, "full timestamp year" to true), mapOf(
            "english month" to ("June" in english), "english year" to ("2026" in english),
            "portuguese month" to ("junho" in portuguese), "portuguese year" to ("2026" in portuguese),
            "another year shows it" to ("2026" in nextYear),
            "full timestamp year" to ("2026" in TimelineDates.fullTimestamp(timestamp, zone, Locale.ENGLISH))))
    }
}
