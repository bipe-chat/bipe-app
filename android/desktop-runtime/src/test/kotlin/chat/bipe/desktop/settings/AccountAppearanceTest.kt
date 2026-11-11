// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import chat.bipe.desktop.account.AccountCloseResult
import chat.bipe.desktop.chat.withMediaOwner as withOwner
import chat.bipe.shared.BipeSyncedSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountAppearanceTest {
    private val policy = AppearancePolicy(listOf("acai", "goiaba", "pitanga"), "acai", listOf("marca", "frutas", "cor", "liso"), "marca")

    @Test fun writesMergeFreshServerValuesAndKeepUnknownKeysAndPrivacyChoices() = runBlocking {
        withOwner { owner ->
            val backend = Backend().apply { server = """{"palette":"pitanga","readReceipts":false,"future":{"preserve":7},"mural":true}""" }
            val model = AccountAppearance.create(owner, policy, backend, Journal())
            model.pickBackground("frutas")
            assertEquals(AppearanceSync.IDLE, model.settled().sync)
            val sent = BipeSyncedSettings.parse(backend.writes.single())
            assertEquals("frutas", BipeSyncedSettings.string(sent, "chatBackground"))
            assertEquals("pitanga", BipeSyncedSettings.string(sent, "palette"))
            assertEquals(false, BipeSyncedSettings.bool(sent, "readReceipts"))
            assertEquals(true, BipeSyncedSettings.bool(sent, "mural"))
            assertEquals(JsonPrimitive(7), sent.getValue("future").jsonObject["preserve"])
        }
    }

    @Test fun staleSdkCacheCannotUndoAnAcknowledgedChoiceAndNewRemoteEventsStillApply() = runBlocking {
        withOwner { owner ->
            val backend = Backend()
            val model = AccountAppearance.create(owner, policy, backend, Journal())
            model.pickPalette("goiaba"); model.settled()
            model.refreshCached()
            assertEquals("goiaba", model.ready().values.palette(policy))
            backend.cache = """{"palette":"pitanga"}"""
            model.refreshCached()
            assertEquals("pitanga", model.ready().values.palette(policy))
        }
    }

    @Test fun malformedFreshSettingsNeverReplaceTheServerEvent() = runBlocking {
        withOwner { owner ->
            val backend = Backend().apply { server = "not a settings object" }
            val model = AccountAppearance.create(owner, policy, backend, Journal())
            model.pickPalette("goiaba")
            assertTrue(model.settled().sync is AppearanceSync.FAILED)
            assertEquals("goiaba", model.ready().values.palette(policy))
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun offlineChoiceIsDurableAndReplaysAfterTheControllerReopens() = runBlocking {
        val journal = Journal()
        withOwner { owner ->
            val model = AccountAppearance.create(owner, policy, Backend().apply { offline = true }, journal)
            model.pickPalette("goiaba")
            assertTrue(model.settled().sync is AppearanceSync.FAILED)
            assertEquals("goiaba", model.ready().values.palette(policy))
        }
        withOwner { owner ->
            val backend = Backend()
            val reopened = AccountAppearance.create(owner, policy, backend, journal)
            assertEquals("goiaba", reopened.ready().values.palette(policy))
            reopened.retry().join()
            assertEquals(AppearanceSync.IDLE, reopened.ready().sync)
            assertEquals("goiaba", BipeSyncedSettings.string(BipeSyncedSettings.parse(backend.writes.single()), "palette"))
        }
    }

    @Test fun failedProtectedWriteKeepsThePreviousChoiceAndRetryRetainsTheRequestedValue() = runBlocking {
        withOwner { owner ->
            val journal = Journal()
            val backend = Backend()
            val model = AccountAppearance.create(owner, policy, backend, journal)
            journal.fail = true
            model.pickPalette("goiaba")
            val failure = model.settled()
            assertEquals("acai", failure.values.palette(policy))
            assertTrue((failure.sync as AppearanceSync.FAILED).action is AppearanceRetry.Edit)
            assertTrue(backend.writes.isEmpty())
            journal.fail = false
            model.retry().join()
            assertEquals("goiaba", model.ready().values.palette(policy))
            assertEquals(AppearanceSync.IDLE, model.ready().sync)
            assertEquals(1, backend.writes.size)
        }
    }

    @Test fun corruptJournalIsPreservedAndCannotBeOverwrittenBySyncOrAChoice() = runBlocking {
        withOwner { owner ->
            val journal = Journal().apply { value = "damaged protected journal" }
            val backend = Backend()
            val model = AccountAppearance.create(owner, policy, backend, journal)
            assertEquals(AppearancePhase.Unavailable, model.phase.value)
            model.pickPalette("goiaba"); model.refreshCached(); model.retry().join()
            assertEquals(AppearancePhase.Unavailable, model.phase.value)
            assertEquals("damaged protected journal", journal.value)
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun retirementRejectsLateWriteCompletionAndLeavesThePendingJournalForRecovery() = runBlocking {
        withOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val journal = Journal()
            val backend = Backend().apply { onWrite = { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
            val model = AccountAppearance.create(owner, policy, backend, journal)
            model.pickPalette("goiaba"); entered.await()
            model.pickPalette("pitanga") // An in-flight action rejects a second tap.
            val closing = owner.closeAsync()
            release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertEquals(AppearanceSync.SAVING, model.ready().sync)
            assertEquals("goiaba", model.ready().values.palette(policy))
            assertEquals(1, backend.writes.size)
            assertEquals(listOf("palette"), Json.parseToJsonElement(journal.value!!).jsonObject.getValue("pending").jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun accountCachesStaySeparateAndUnknownChoiceIdsUseSharedDefaultsWithoutWriting() = runBlocking {
        withOwner { first -> withOwner { second ->
            val a = AccountAppearance.create(first, policy, Backend().apply { cache = """{"palette":"retired","chatBackground":"unknown"}""" }, Journal())
            val bBackend = Backend().apply { cache = """{"palette":"pitanga","chatBackground":"liso"}""" }
            val b = AccountAppearance.create(second, policy, bBackend, Journal())
            a.pickPalette("invalid"); a.pickBackground("invalid")
            assertEquals("acai", a.ready().values.palette(policy))
            assertEquals("marca", a.ready().values.background(policy))
            assertEquals("pitanga", b.ready().values.palette(policy))
            assertEquals("liso", b.ready().values.background(policy))
            assertTrue(bBackend.writes.isEmpty())
        } }
    }

    private fun AccountAppearance.ready() = phase.value as AppearancePhase.Ready
    private suspend fun AccountAppearance.settled() = withTimeout(3_000) {
        phase.first { it is AppearancePhase.Ready && it.sync != AppearanceSync.SAVING } as AppearancePhase.Ready
    }
    private class Journal : AppearanceJournal {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }
    private class Backend : AppearanceBackend {
        var cache: String? = """{"palette":"acai","chatBackground":"marca"}"""
        var server: String? = cache
        var offline = false
        var onWrite: suspend () -> Unit = {}
        val writes = mutableListOf<String>()
        override suspend fun cached() = cache
        override suspend fun fresh(): String? { check(!offline); return server }
        override suspend fun write(value: String) { onWrite(); writes += value; server = value }
    }
}
