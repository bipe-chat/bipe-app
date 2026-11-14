// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RoomTypingTest {
    @Test fun onIsThrottledForFourSecondsAndBlankStopsWithoutLosingTheNextBurst() = runBlocking {
        withMediaOwner { owner ->
            val clock = AtomicLong(); val calls = Channel<Boolean>(Channel.UNLIMITED)
            val group = AccountTaskGroup.create(owner)
            val typing = group.own(RoomTyping(group, "own", "Person", backend(calls), now = clock::get))
            typing.available(true); typing.visible(true); typing.draftChanged(true)
            assertTrue(withTimeout(2_000) { calls.receive() })
            clock.set(3_999); typing.draftChanged(true); typing.draftChanged(false)
            assertFalse(withTimeout(2_000) { calls.receive() })
            assertTrue(calls.tryReceive().isFailure)
            typing.draftChanged(true); assertTrue(withTimeout(2_000) { calls.receive() })
            clock.set(8_000); typing.draftChanged(true); assertTrue(withTimeout(2_000) { calls.receive() })
            group.close(); assertFalse(withTimeout(2_000) { calls.receive() })
            group.close(); assertTrue(calls.tryReceive().isFailure)
        }
    }

    @Test fun backgroundAndPrivacyStopAndFreshPrivacyIsCheckedEvenBeforeItsQueuedNotification() = runBlocking {
        withMediaOwner { owner ->
            val calls = Channel<Boolean>(Channel.UNLIMITED); val allowed = AtomicBoolean(true)
            val group = AccountTaskGroup.create(owner)
            val typing = group.own(RoomTyping(group, "own", "Person", backend(calls), allowed::get))
            typing.available(true); typing.visible(true); typing.draftChanged(true)
            assertTrue(withTimeout(2_000) { calls.receive() })
            typing.visible(false); assertFalse(withTimeout(2_000) { calls.receive() })
            typing.visible(true); allowed.set(false); typing.draftChanged(true)
            typing.available(false); group.close()
            assertTrue(calls.tryReceive().isFailure)
        }
    }

    @Test fun lateNameResultsCannotReappearAfterTheListClearsOrExposeRawIds() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = Channel<Boolean>(Channel.UNLIMITED); val group = AccountTaskGroup.create(owner)
            val typing = group.own(RoomTyping(group, "own", "Person", object : TypingBackend {
                override suspend fun notice(typing: Boolean) { calls.send(typing) }
                override suspend fun name(userId: String): String? {
                    if (userId == "old") { entered.complete(Unit); release.await(); return "Old" }
                    return if (userId == "new") "New" else "@raw:example.test"
                }
            }))
            typing.available(true); typing.visible(true); typing.incoming(listOf("own", "old")); entered.await()
            typing.incoming(emptyList()); release.complete(Unit)
            typing.incoming(listOf("own", "new", "@raw:example.test", "new", "third"))
            val shown = withTimeout(2_000) { typing.phase.first { it is TypingPhase.Showing } } as TypingPhase.Showing
            assertEquals(TypingPeople("New", "Person", 3), shown.people)
            typing.available(false)
            withTimeout(2_000) { typing.phase.first { it == TypingPhase.Hidden } }
            assertTrue(calls.tryReceive().isFailure)
        }
    }

    @Test fun aPossiblyDeliveredFailedOnStillGetsAnOffBeforeItsRoomIsReleased() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val calls = Channel<Boolean>(Channel.UNLIMITED)
            val group = AccountTaskGroup.create(owner)
            val typing = group.own(RoomTyping(group, "own", "Person", object : TypingBackend {
                override suspend fun notice(typing: Boolean) {
                    calls.send(typing)
                    if (typing) { entered.complete(Unit); error("Synthetic notice failure") }
                }
                override suspend fun name(userId: String): String? = null
            }))
            typing.available(true); typing.visible(true); typing.draftChanged(true); entered.await()
            group.close()
            assertTrue(calls.receive()); assertFalse(calls.receive()); assertTrue(calls.tryReceive().isFailure)
            typing.draftChanged(true); assertTrue(calls.tryReceive().isFailure)
        }
    }

    private fun backend(calls: Channel<Boolean>) = object : TypingBackend {
        override suspend fun notice(typing: Boolean) { calls.send(typing) }
        override suspend fun name(userId: String): String? = null
    }

    @Test fun retiringARoomJoinsItsLateNameLookupWithoutPublishingIntoAnotherAccount() = runBlocking {
        withMediaOwner { old -> withMediaOwner { current ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val oldGroup = AccountTaskGroup.create(old); val currentGroup = AccountTaskGroup.create(current)
            val previous = oldGroup.own(RoomTyping(oldGroup, "own", "Person", object : TypingBackend {
                override suspend fun notice(typing: Boolean) { }
                override suspend fun name(userId: String): String = withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); "Old account"
                }
            }))
            val next = currentGroup.own(RoomTyping(currentGroup, "own", "Person", object : TypingBackend {
                override suspend fun notice(typing: Boolean) { }
                override suspend fun name(userId: String) = "Current account"
            }))
            previous.available(true); previous.visible(true); previous.incoming(listOf("peer")); entered.await()
            next.available(true); next.visible(true); next.incoming(listOf("peer"))
            withTimeout(2_000) { next.phase.first { it is TypingPhase.Showing } }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { oldGroup.close() }
            release.complete(Unit); closing.await()
            assertEquals(TypingPhase.Hidden, previous.phase.value)
            assertEquals("Current account", (next.phase.value as TypingPhase.Showing).people.first)
        } }
    }
}
