// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.ReceiptType

class AccountReadActionsTest {
    @Test fun dmReceiptsRespectPrivacyWhileGroupsStayPublicAndMalformedPreferencesFailClosed() {
        assertEquals(ReceiptType.READ, AccountReadActions.receipt(true, null))
        assertEquals(ReceiptType.READ_PRIVATE, AccountReadActions.receipt(true, """{"readReceipts":false}"""))
        assertEquals(ReceiptType.READ, AccountReadActions.receipt(false, """{"readReceipts":false}"""))
        for (bad in listOf("broken", "[]", """{"readReceipts":"false"}""")) assertTrue(runCatching { AccountReadActions.receipt(true, bad) }.isFailure)
    }
    @Test fun failedReadKeepsItsOriginalRoomAndRetryCannotUseAnotherSelectedRoom() = runBlocking {
        withMediaOwner { owner ->
            var offline = true
            val calls = mutableListOf<ReadIntent>()
            val model = AccountReadActions.create(owner) { intent -> calls += intent; check(!offline) }
            model.set("original", false); model.idle()
            offline = false
            model.set("other", true); model.idle()
            assertEquals(setOf("original"), model.phase.value.failures.keys)
            model.retry(); model.idle()
            assertEquals(listOf(ReadIntent("original", false), ReadIntent("other", true), ReadIntent("original", false)), calls)
            assertTrue(model.phase.value.failures.isEmpty())
        }
    }
    @Test fun aSecondClickDuringTheReadWriteIsIgnoredAndRetirementDropsItsLateResult() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<ReadIntent>()
            val model = AccountReadActions.create(owner) { intent -> withContext(NonCancellable) { calls += intent; entered.complete(Unit); release.await() } }
            model.set("original", true); entered.await(); model.set("other", false)
            val closing = owner.closeAsync(); release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertTrue(model.phase.value is ReadActionPhase.Sending)
            assertEquals(listOf(ReadIntent("original", true)), calls)
        }
    }
    private suspend fun AccountReadActions.idle() = withTimeout(3_000) { phase.first { it is ReadActionPhase.Idle } }
}
