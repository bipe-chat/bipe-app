// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.invites.deliverInviteNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteDeliveryTest {
    @Test fun cancellationDuringClaimRestoresWithoutSending() = runBlocking {
        val claimed = CompletableDeferred<Unit>()
        val finishClaim = CompletableDeferred<Unit>()
        var saved: String? = "Synthetic first message"
        var sends = 0
        val sender = launch {
            deliverInviteNote(
                claim = { val note = saved; saved = null; claimed.complete(Unit); finishClaim.await(); note },
                restore = { saved = it },
                send = { sends++ },
            )
        }
        claimed.await()
        sender.cancel()
        finishClaim.complete(Unit)
        sender.join()
        assertEquals("Synthetic first message", saved)
        assertEquals(0, sends)
    }

    @Test fun cancellationDuringSendRestoresBeforeStopping() = runBlocking {
        val sending = CompletableDeferred<Unit>()
        var saved: String? = "Synthetic first message"
        val sender = launch {
            deliverInviteNote(
                claim = { saved.also { saved = null } },
                restore = { saved = it },
                send = { sending.complete(Unit); awaitCancellation() },
            )
        }
        sending.await()
        sender.cancelAndJoin()
        assertEquals("Synthetic first message", saved)
    }

    @Test fun successfulDeliveryConsumesTheNoteOnlyOnce() = runBlocking {
        var saved: String? = "Synthetic first message"
        val sent = mutableListOf<String>()
        suspend fun deliver() = deliverInviteNote(
            claim = { saved.also { saved = null } },
            restore = { saved = it },
            send = { sent += it },
        )
        assertTrue(deliver())
        assertFalse(deliver())
        assertEquals(listOf("Synthetic first message"), sent)
    }
}
