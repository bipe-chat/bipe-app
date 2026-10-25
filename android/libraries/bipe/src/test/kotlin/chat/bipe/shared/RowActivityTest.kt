// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.BipeRowActivity.Kind
import chat.bipe.shared.BipeRowActivity.LocalEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Synthetic rooms for the chat-list row date (bipe-chat/bipe#592). The same cases run in RowActivityTests.swift. */
class RowActivityTest {
    @Test fun sdkLatestEventWins() {
        assertThat(BipeRowActivity.needsLocalTimeline(1_700_000_000_000)).isFalse()
        val result = BipeRowActivity.resolve(1_700_000_000_000, listOf(LocalEvent(1_800_000_000_000, Kind.MESSAGE)), 1_900_000_000_000)
        assertThat(result).isEqualTo(1_700_000_000_000)
    }

    /** The device test of 25 Oct 2026: an unapproved phone, every recent event undecryptable, no SDK latest event. */
    @Test fun roomWhoseLatestEventIsUndecryptableKeepsItsDate() {
        assertThat(BipeRowActivity.needsLocalTimeline(null)).isTrue()
        val events = listOf(
            LocalEvent(1_700_000_000_000, Kind.MESSAGE),
            LocalEvent(1_700_000_500_000, Kind.MESSAGE), // undecryptable
            LocalEvent(1_700_000_900_000, Kind.OTHER), // a member left
        )
        assertThat(BipeRowActivity.resolve(null, events, null)).isEqualTo(1_700_000_500_000)
    }

    @Test fun roomWithOnlyStateEventsUsesTheNewestEvent() {
        val events = listOf(LocalEvent(1_600_000_000_000, Kind.OTHER), LocalEvent(1_600_000_100_000, Kind.OTHER))
        assertThat(BipeRowActivity.resolve(null, events, null)).isEqualTo(1_600_000_100_000)
    }

    @Test fun knownTimeStaysWhenTheTimelineIsEmptyOrOlder() {
        assertThat(BipeRowActivity.resolve(null, emptyList(), 1_650_000_000_000)).isEqualTo(1_650_000_000_000)
        val older = listOf(LocalEvent(1_640_000_000_000, Kind.MESSAGE))
        assertThat(BipeRowActivity.resolve(null, older, 1_650_000_000_000)).isEqualTo(1_650_000_000_000)
    }

    /** No local data: the row has no date. It never takes the current time. */
    @Test fun unknownStaysUnknown() {
        assertThat(BipeRowActivity.resolve(null, emptyList(), null)).isNull()
    }
}
