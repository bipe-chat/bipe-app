// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.BipeRowActivity.Kind
import chat.bipe.shared.BipeRowActivity.LocalEvent
import chat.bipe.shared.BipeRowActivity.Row
import chat.bipe.shared.BipeRowActivity.SdkLatest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Synthetic rooms for the chat-list row date and text (bipe-chat/bipe#592). The same cases run in RowActivityTests.swift. */
class RowActivityTest {
    @Test fun sdkLatestEventWinsWhenItIsTheNewest() {
        val row = BipeRowActivity.resolve(SdkLatest(1_700_000_000_000, "oi", isIncoming = true),
            listOf(LocalEvent(1_600_000_000_000, Kind.MESSAGE, "old")), 1_900_000_000_000)
        assertThat(row).isEqualTo(Row(1_700_000_000_000, "oi", 1_700_000_000_000))
    }

    /** The device test of 26 Oct 2026: the message arrived before its room key, so the SDK kept the own join. */
    @Test fun newerDecryptedLocalMessageReplacesTheSdkJoin() {
        val join = SdkLatest(1_700_000_000_000, null, isIncoming = false)
        val events = listOf(LocalEvent(1_700_000_000_000, Kind.OTHER), LocalEvent(1_700_000_060_000, Kind.MESSAGE, "oi", isOwn = false))
        assertThat(BipeRowActivity.resolve(join, events, null)).isEqualTo(Row(1_700_000_060_000, "oi", 1_700_000_060_000))
    }

    /** A newer message that still does not decrypt shows the undecryptable label with its own time. */
    @Test fun newerUndecryptableLocalMessageShowsItsLabel() {
        val sdk = SdkLatest(1_700_000_000_000, "before", isIncoming = true)
        val events = listOf(LocalEvent(1_700_000_500_000, Kind.MESSAGE, "undecryptable", isOwn = false))
        assertThat(BipeRowActivity.resolve(sdk, events, null)).isEqualTo(Row(1_700_000_500_000, "undecryptable", 1_700_000_500_000))
    }

    /** An own newer message does not count as incoming (the archive keeps the chat archived). */
    @Test fun ownNewerLocalMessageIsNotIncoming() {
        val sdk = SdkLatest(1_700_000_000_000, "oi", isIncoming = true)
        val events = listOf(LocalEvent(1_700_000_100_000, Kind.MESSAGE, "tudo bem?", isOwn = true))
        assertThat(BipeRowActivity.resolve(sdk, events, null)).isEqualTo(Row(1_700_000_100_000, "tudo bem?", null))
    }

    /** A newer state event (a member left) never replaces the SDK latest event. */
    @Test fun newerStateEventKeepsTheSdkLatestEvent() {
        val sdk = SdkLatest(1_700_000_000_000, "oi", isIncoming = false)
        assertThat(BipeRowActivity.resolve(sdk, listOf(LocalEvent(1_700_000_900_000, Kind.OTHER)), null))
            .isEqualTo(Row(1_700_000_000_000, "oi", null))
    }

    /** The device test of 25 Oct 2026: an unapproved phone, every recent event undecryptable, no SDK latest event. */
    @Test fun roomWhoseLatestEventIsUndecryptableKeepsItsDate() {
        val events = listOf(
            LocalEvent(1_700_000_000_000, Kind.MESSAGE, "a"),
            LocalEvent(1_700_000_500_000, Kind.MESSAGE, "undecryptable"),
            LocalEvent(1_700_000_900_000, Kind.OTHER), // a member left
        )
        val row = BipeRowActivity.resolve(null, events, null)
        assertThat(row.timestampMs).isEqualTo(1_700_000_500_000)
        assertThat(row.previewText).isEqualTo("undecryptable")
    }

    @Test fun roomWithOnlyStateEventsUsesTheNewestEvent() {
        val events = listOf(LocalEvent(1_600_000_000_000, Kind.OTHER), LocalEvent(1_600_000_100_000, Kind.OTHER))
        assertThat(BipeRowActivity.resolve(null, events, null)).isEqualTo(Row(1_600_000_100_000, null, null))
    }

    @Test fun knownTimeStaysWhenTheTimelineIsEmptyOrOlder() {
        assertThat(BipeRowActivity.resolve(null, emptyList(), 1_650_000_000_000).timestampMs).isEqualTo(1_650_000_000_000)
        val older = listOf(LocalEvent(1_640_000_000_000, Kind.MESSAGE, "old"))
        assertThat(BipeRowActivity.resolve(null, older, 1_650_000_000_000)).isEqualTo(Row(1_650_000_000_000, null, null))
    }

    /** No local data: the row has no date. It never takes the current time. */
    @Test fun unknownStaysUnknown() {
        assertThat(BipeRowActivity.resolve(null, emptyList(), null)).isEqualTo(Row(null, null, null))
    }
}
