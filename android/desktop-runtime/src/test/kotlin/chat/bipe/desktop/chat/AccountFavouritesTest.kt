// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import chat.bipe.shared.BipeFavouritesOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountFavouritesTest {
    @Test fun reorderingMergesFreshOtherScopePositionsAndUnknownFields() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend().apply { server = """{"rooms":["a","group","b"],"future":{"keep":true}}""" }
            val model = AccountFavourites.create(owner, backend, Journal())
            model.move("a", 1, listOf("a", "b"))
            assertEquals(FavouritesSync.Idle, model.settled().sync)
            assertEquals(listOf("b", "group", "a"), model.ready().order)
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(backend.writes.single()).jsonObject.getValue("future").jsonObject["keep"])
            assertEquals(listOf("a"), backend.checks)
        }
    }

    @Test fun offlineTagAndOrderIntentSurvivesReopeningAndReplays() = runBlocking {
        val journal = Journal()
        withMediaOwner { owner ->
            val backend = Backend().apply { offline = true }
            val model = AccountFavourites.create(owner, backend, journal)
            model.set("c", true)
            assertTrue(model.settled().sync is FavouritesSync.Failed)
            assertEquals(listOf("a", "b", "c"), model.ready().order)
            assertTrue(backend.tags.isEmpty())
        }
        withMediaOwner { owner ->
            val backend = Backend()
            val model = AccountFavourites.create(owner, backend, journal)
            assertEquals(listOf("a", "b", "c"), model.ready().order)
            model.retry().join()
            assertEquals(FavouritesSync.Idle, model.ready().sync)
            assertEquals(listOf("c" to true), backend.tags)
            assertEquals(listOf("a", "b", "c"), BipeFavouritesOrder.parse(backend.writes.single()))
        }
    }

    @Test fun acceptedTagWithFailedOrderWriteIsRetriedWithoutDroppingRemoteRooms() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend().apply { failWrite = true }
            val model = AccountFavourites.create(owner, backend, Journal())
            model.set("a", false)
            assertTrue(model.settled().sync is FavouritesSync.Failed)
            assertEquals(listOf("a" to false), backend.tags)
            backend.server = """{"rooms":["a","b","remote"],"future":7}"""
            backend.failWrite = false
            model.retry().join()
            assertEquals(FavouritesSync.Idle, model.ready().sync)
            assertEquals(listOf("b", "remote"), model.ready().order)
            assertEquals(listOf("a" to false, "a" to false), backend.tags)
            assertEquals(JsonPrimitive(7), Json.parseToJsonElement(backend.writes.single()).jsonObject["future"])
        }
    }

    @Test fun failedProtectedWriteKeepsPreviousOrderAndTheNextChoiceRetainsBothIntents() = runBlocking {
        withMediaOwner { owner ->
            val journal = Journal()
            val backend = Backend()
            val model = AccountFavourites.create(owner, backend, journal)
            journal.fail = true
            model.set("c", true)
            assertTrue(model.settled().sync is FavouritesSync.Failed)
            assertEquals(listOf("a", "b"), model.ready().order)
            assertTrue(backend.tags.isEmpty())
            journal.fail = false
            model.set("d", true)
            assertEquals(FavouritesSync.Idle, model.settled().sync)
            assertEquals(listOf("a", "b", "c", "d"), model.ready().order)
            assertEquals(listOf("c" to true, "d" to true), backend.tags)
        }
    }

    @Test fun anUnchangedSdkCacheCannotUndoAnAcknowledgedOrder() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend()
            val model = AccountFavourites.create(owner, backend, Journal())
            model.move("b", 0, listOf("a", "b")); model.settled()
            model.refreshCached()
            assertEquals(listOf("b", "a"), model.ready().order)
            backend.cache = """{"rooms":["remote","b","a"]}"""
            model.refreshCached()
            assertEquals(listOf("remote", "b", "a"), model.ready().order)
        }
    }

    @Test fun corruptJournalIsRetainedAndCannotBeOverwrittenByActionsOrSync() = runBlocking {
        withMediaOwner { owner ->
            val journal = Journal().apply { value = "damaged protected record" }
            val backend = Backend()
            val model = AccountFavourites.create(owner, backend, journal)
            model.set("c", true); model.move("a", 1, listOf("a", "b")); model.refreshCached(); model.retry().join()
            assertEquals(FavouritesPhase.Unavailable, model.phase.value)
            assertEquals("damaged protected record", journal.value)
            assertTrue(backend.tags.isEmpty()); assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun retiringAnAccountSuppressesLateAcknowledgementAndKeepsItsPendingJournal() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val journal = Journal()
            val backend = Backend().apply { onWrite = { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
            val model = AccountFavourites.create(owner, backend, journal)
            model.set("c", true); entered.await()
            model.set("d", true) // A second in-flight action is rejected.
            val closing = owner.closeAsync()
            release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertEquals(FavouritesSync.Saving, model.ready().sync)
            assertEquals(listOf("a", "b", "c"), model.ready().order)
            assertEquals(listOf("c" to true), backend.tags)
            assertEquals(1, Json.parseToJsonElement(journal.value!!).jsonObject.getValue("pending").jsonArray.size)
        }
    }

    @Test fun invalidMoveArgumentsDoNotWriteAndSeparateAccountsKeepSeparateOrders() = runBlocking {
        withMediaOwner { a -> withMediaOwner { b ->
            val firstBackend = Backend()
            val first = AccountFavourites.create(a, firstBackend, Journal())
            val second = AccountFavourites.create(b, Backend().apply { cache = """{"rooms":["other"]}""" }, Journal())
            first.move("a", 4, listOf("a", "b")); first.move("missing", 0, listOf("a", "b")); first.move("a", 0, listOf("a", "a"))
            assertEquals(listOf("a", "b"), first.ready().order)
            assertEquals(listOf("other"), second.ready().order)
            assertTrue(firstBackend.writes.isEmpty())
        } }
    }

    private fun AccountFavourites.ready() = phase.value as FavouritesPhase.Ready
    private suspend fun AccountFavourites.settled() = withTimeout(3_000) {
        phase.first { it is FavouritesPhase.Ready && it.sync != FavouritesSync.Saving } as FavouritesPhase.Ready
    }
    private class Journal : FavouritesJournal {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }
    private class Backend : FavouritesBackend {
        var cache: String? = """{"rooms":["a","b"]}"""
        var server: String? = cache
        var offline = false
        var failWrite = false
        var onWrite: suspend () -> Unit = {}
        val tags = mutableListOf<Pair<String, Boolean>>()
        val checks = mutableListOf<String>()
        val writes = mutableListOf<String>()
        override suspend fun cached() = cache
        override suspend fun fresh(): String? { check(!offline); return server }
        override suspend fun setFavourite(room: String, value: Boolean) { tags += room to value }
        override suspend fun checkFavourite(room: String) { checks += room }
        override suspend fun write(value: String) { check(!failWrite); onWrite(); writes += value; server = value }
    }
}
