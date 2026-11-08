// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.ListType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.nio.file.Files

/**
 * SignedLists (bipe-chat/bipe#645): the bundled lists, the stored envelope and the "keep the current list" rule.
 * bipe-core's own CI runs every case of test-vectors/signed-lists.json; these check the app's use of it.
 * Same cases as BipeKit SignedListsTests.
 */
class SignedListsTest {
    @Test
    fun bundledListsVerify() {
        val lists = SignedLists(null)
        val operators = lists.current(ListType.OPERATORS)!!
        assertThat(operators.version.toLong()).isAtLeast(1L)
        assertThat(lists.current(ListType.ISSUERS)).isNotNull()
        assertThat(operators.operators!!.region.coreApi).startsWith("https://")
    }

    @Test
    fun defaultServerCountsAlsoWhenStale() {
        val lists = SignedLists(null)
        // Year 2100: every bundled list is stale by then.
        val nowValues = listOf<Long?>(null, 4_102_444_800L)
        val expected = mapOf("bipe.net.br" to true, "matrix.org" to false)
        for ((server, bipe) in expected) {
            for (now in nowValues) {
                val result = if (now == null) lists.isBipeServer(server) else lists.isBipeServer(server, now)
                assertWithMessage("$server at $now").that(result).isEqualTo(bipe)
            }
        }
    }

    @Test
    fun rejectedEnvelopeKeepsTheCurrentList() {
        val lists = SignedLists(null)
        val before = lists.current(ListType.OPERATORS)
        val now = System.currentTimeMillis() / 1000
        val empty = lists.accept("{}", ListType.OPERATORS, now)
        val issuersAsOperators = lists.accept(SignedLists.bundled(ListType.ISSUERS), ListType.OPERATORS, now)
        assertThat(empty).isNotEqualTo("valid")
        assertThat(issuersAsOperators).isEqualTo("wrong_type")
        assertThat(lists.current(ListType.OPERATORS)).isEqualTo(before)
    }

    @Test
    fun acceptedEnvelopeIsStoredAndLoadedAgain() {
        val dir = Files.createTempDirectory("signed-lists").toFile()
        try {
            val lists = SignedLists(dir)
            val bundled = SignedLists.bundled(ListType.OPERATORS)
            val issued = lists.current(ListType.OPERATORS)!!.issued
            // The same version with the same payload is valid; it is stored as received.
            assertThat(lists.accept(bundled, ListType.OPERATORS, issued)).isEqualTo("valid")
            assertThat(dir.resolve("operators.json").readText()).isEqualTo(bundled)
            assertThat(SignedLists(dir).current(ListType.OPERATORS)).isEqualTo(lists.current(ListType.OPERATORS))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storedEnvelopeThatFailsIsIgnored() {
        val list = SignedLists.newest(SignedLists.bundled(ListType.OPERATORS), "not json", ListType.OPERATORS)
        assertThat(list?.version).isEqualTo(SignedLists(null).current(ListType.OPERATORS)?.version)
    }

    @Test
    fun upperCaseServerIsNotBipe() {
        val cases = listOf("@ana:bipe.net.br" to true, "@ana:BIPE.NET.BR" to false)
        cases.forEach { (userId, bipe) -> assertWithMessage(userId).that(BipeServers.isBipeUserId(userId)).isEqualTo(bipe) }
    }
}
