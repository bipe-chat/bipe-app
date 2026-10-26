// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.BipeRowActivity.Kind.MESSAGE
import chat.bipe.shared.BipeRowActivity.Kind.OTHER
import chat.bipe.shared.BipeRowActivity.LocalEvent
import chat.bipe.shared.BipeRowActivity.Row
import chat.bipe.shared.BipeRowActivity.SdkLatest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Synthetic rooms for the chat-list row date and text (bipe-chat/bipe#592), one table. The same cases run in
 * RowActivityTests.swift.
 */
class RowActivityTest {
    private class Case(val name: String, val sdk: SdkLatest?, val events: List<LocalEvent>, val knownMs: Long?, val row: Row)

    private val t = 1_700_000_000_000L

    private val cases = listOf(
        Case("the SDK latest event wins when it is the newest",
            SdkLatest(t, "oi", true), listOf(LocalEvent(t - 100_000_000_000, MESSAGE, "old")), t + 200_000_000_000,
            Row(t, "oi", t)),
        // The device test of 26 Oct 2026: the message arrived before its room key, so the SDK kept the own join.
        Case("a newer decrypted local message replaces the SDK join",
            SdkLatest(t, null, false), listOf(LocalEvent(t, OTHER), LocalEvent(t + 60_000, MESSAGE, "oi")), null,
            Row(t + 60_000, "oi", t + 60_000)),
        Case("a newer undecryptable local message shows its label",
            SdkLatest(t, "before", true), listOf(LocalEvent(t + 500_000, MESSAGE, "undecryptable")), null,
            Row(t + 500_000, "undecryptable", t + 500_000)),
        Case("an own newer local message is not incoming",
            SdkLatest(t, "oi", true), listOf(LocalEvent(t + 100_000, MESSAGE, "tudo bem?", isOwn = true)), null,
            Row(t + 100_000, "tudo bem?", null)),
        Case("a newer state event keeps the SDK latest event",
            SdkLatest(t, "oi", false), listOf(LocalEvent(t + 900_000, OTHER)), null,
            Row(t, "oi", null)),
        // The device test of 25 Oct 2026: an unapproved phone, every recent event undecryptable, no SDK latest event.
        Case("a room whose latest event is undecryptable keeps its date",
            null, listOf(LocalEvent(t, MESSAGE, "a"), LocalEvent(t + 500_000, MESSAGE, "undecryptable"), LocalEvent(t + 900_000, OTHER)), null,
            Row(t + 500_000, "undecryptable", t + 500_000)),
        Case("a room with only state events uses the newest event",
            null, listOf(LocalEvent(t - 100_000_000_000, OTHER), LocalEvent(t - 99_999_900_000, OTHER)), null,
            Row(t - 99_999_900_000, null, null)),
        Case("a known time stays when the timeline is older",
            null, listOf(LocalEvent(t - 60_000_000_000, MESSAGE, "old")), t - 50_000_000_000,
            Row(t - 50_000_000_000, null, null)),
        Case("a known time stays when the timeline is empty",
            null, emptyList(), t - 50_000_000_000,
            Row(t - 50_000_000_000, null, null)),
        // No local data: the row has no date. It never takes the current time.
        Case("unknown stays unknown", null, emptyList(), null, Row(null, null, null)),
    )

    @Test fun everyCaseResolvesToItsRow() {
        for (case in cases) {
            assertWithMessage(case.name).that(BipeRowActivity.resolve(case.sdk, case.events, case.knownMs)).isEqualTo(case.row)
        }
    }
}
