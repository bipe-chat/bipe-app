// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeHandleChange
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** The placement rule of ADR 0066, the same as BipeKit BipeHandleChangePlacement. */
class BipeHandleChangesTest {
    private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)
    private fun change(user: String, seconds: Long) = BipeHandleChange(user, old = "a", new = "b", at = at(seconds))

    private val anchors = listOf(null, at(10), at(20), null, at(30))

    @Test
    fun linesGoBeforeTheFirstNewerRow() {
        val placed = BipeHandleChangePlacement.placements(listOf(change("@x", 15), change("@x", 25)), anchors, setOf("@x"), emptyMap(), includesEnd = false)
        assertThat(placed.map { it.first }).containsExactly(2, 4).inOrder()
    }

    @Test
    fun olderThanTheLoadedRangeOrNotAMemberGetsNoLine() {
        val placed = BipeHandleChangePlacement.placements(listOf(change("@x", 5), change("@y", 15)), anchors, setOf("@x"), emptyMap(), includesEnd = true)
        assertThat(placed).isEmpty()
    }

    @Test
    fun newerThanTheLastRowShowsOnlyWhenLive() {
        val changes = listOf(change("@x", 40))
        assertThat(BipeHandleChangePlacement.placements(changes, anchors, setOf("@x"), emptyMap(), includesEnd = false)).isEmpty()
        assertThat(BipeHandleChangePlacement.placements(changes, anchors, setOf("@x"), emptyMap(), includesEnd = true).map { it.first }).containsExactly(5)
    }

    @Test
    fun aChangeBeforeTheJoinGetsNoLine() {
        val placed = BipeHandleChangePlacement.placements(listOf(change("@x", 15)), anchors, setOf("@x"), mapOf("@x" to at(18)), includesEnd = true)
        assertThat(placed).isEmpty()
    }

    @Test
    fun theLineIdIsStable() {
        assertThat(change("@x:bipe.chat", 15).lineId).isEqualTo("bipe-handle-change-@x:bipe.chat-15000")
    }
}
