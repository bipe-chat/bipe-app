// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.nio.file.Files
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.*
import org.junit.Test

class ChatArchiveTest {
    @Test fun serverSyncCases() {
        val file = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors/archive-sync.json")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        for (item in cases) {
            val v = item.jsonObject
            val expected = v.getValue("expected").jsonObject
            val result = chat.bipe.shared.generated.BipeChatArchive.reconcile(
                v.getValue("marker").jsonPrimitive.longOrNull,
                v.getValue("synced").jsonPrimitive.boolean,
                v.getValue("serverArchived").jsonPrimitive.boolean,
                v.getValue("activity").jsonPrimitive.longOrNull,
                v.getValue("incoming").jsonPrimitive.longOrNull,
            )
            val name = v.getValue("name").jsonPrimitive.content
            assertEquals(name, expected.getValue("marker").jsonPrimitive.longOrNull, result.marker)
            assertEquals(name, expected.getValue("synced").jsonPrimitive.boolean, result.synced)
            assertEquals(name, expected.getValue("tagWrite").jsonPrimitive.booleanOrNull, result.tagWrite)
        }
    }

    @Test fun migrationMetadataIsAtomicAndLegacyCompatible() {
        val directory = Files.createTempDirectory("bipe-archive-migration").toFile()
        try {
            val store = BipeChatArchiveStore(directory, "@a:example.invalid")
            store.save(mapOf("!room:example.invalid" to 100L))
            assertTrue(store.loadState().syncedRooms.isEmpty())
            store.save(mapOf("!room:example.invalid" to 100L), setOf("!room:example.invalid"))
            assertEquals(setOf("!room:example.invalid"), store.loadState().syncedRooms)
            assertThrows(Exception::class.java) { store.save(emptyMap(), setOf("!room:example.invalid")) }
            assertEquals(mapOf("!room:example.invalid" to 100L), store.loadState().markers)
            assertEquals(setOf("!room:example.invalid"), store.loadState().syncedRooms)
            store.save(emptyMap())
            assertTrue(store.loadState().syncedRooms.isEmpty())
            store.file.writeText("""{"version":1,"markers":{"!old:example.invalid":50}}""")
            assertEquals(mapOf("!old:example.invalid" to 50L), store.loadState().markers)
            assertTrue(store.loadState().syncedRooms.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun incomingRestoresButOwnOrOldActivityDoesNot() {
        val policy = chat.bipe.shared.generated.BipeChatArchive
        assertTrue(policy.isArchived(100L, null))
        assertTrue(policy.isArchived(100L, 100L))
        assertTrue(policy.isArchived(100L, 99L))
        assertFalse(policy.isArchived(100L, 101L))
        assertTrue(policy.isArchived(0L, null))
        assertFalse(policy.isArchived(null, 100L))
    }

    @Test fun persistsOfflineAndIsolatesOwners() {
        val directory = Files.createTempDirectory("bipe-archive-test").toFile()
        try {
            val a = BipeChatArchiveStore(directory, "@a:example.invalid")
            val b = BipeChatArchiveStore(directory, "@b:example.invalid")
            a.save(mapOf("!room:example.invalid" to 100L))
            assertEquals(mapOf("!room:example.invalid" to 100L), BipeChatArchiveStore(directory, "@a:example.invalid").load())
            assertTrue(b.load().isEmpty())
            assertFalse(a.file.name.contains("@a"))
            assertThrows(Exception::class.java) { a.save(mapOf("!invalid" to -1L)) }
            assertEquals(mapOf("!room:example.invalid" to 100L), a.load())
            a.save(emptyMap())
            assertTrue(a.load().isEmpty())
            a.file.writeText("corrupt")
            assertThrows(Exception::class.java) { a.load() }
            assertThrows(Exception::class.java) { a.save(emptyMap()) }
            assertEquals("corrupt", a.file.readText())
        } finally { directory.deleteRecursively() }
    }
}
