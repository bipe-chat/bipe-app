// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeChatArchive
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatArchiveTest {
    @get:Rule val disk = TemporaryFolder()
    private val room = "!room:example.invalid"
    private val saved = mapOf(room to 100L)
    private fun store(owner: String = "@a:example.invalid") = BipeChatArchiveStore(disk.root, owner)
    private fun state(synced: Boolean = false) = BipeChatArchiveStore.State(saved, if (synced) setOf(room) else emptySet())

    @Test fun serverSyncCases() {
        val file = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors/archive-sync.json")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        for (item in cases) {
            val v = item.jsonObject
            val expected = v.getValue("expected").jsonObject
            val result = BipeChatArchive.reconcile(
                v.getValue("marker").jsonPrimitive.longOrNull,
                v.getValue("synced").jsonPrimitive.boolean,
                v.getValue("serverArchived").jsonPrimitive.boolean,
                v.getValue("activity").jsonPrimitive.longOrNull,
                v.getValue("incoming").jsonPrimitive.longOrNull,
            )
            assertThat(listOf(result.marker, result.synced, result.tagWrite))
                .named(v.getValue("name").jsonPrimitive.content)
                .isEqualTo(listOf(expected.getValue("marker").jsonPrimitive.longOrNull,
                    expected.getValue("synced").jsonPrimitive.boolean,
                    expected.getValue("tagWrite").jsonPrimitive.booleanOrNull))
        }
    }

    @Test fun migrationMetadataTravelsWithItsMarker() {
        val archive = store()
        archive.save(saved)
        assertThat(archive.loadState()).isEqualTo(state())
        archive.save(saved, setOf(room))
        assertThat(store().loadState()).isEqualTo(state(synced = true))
        val beforeInvalidSave = archive.file.readBytes().toList()
        assertThrows(IOException::class.java) { archive.save(emptyMap(), setOf(room)) }
        assertThat(archive.file.readBytes().toList()).isEqualTo(beforeInvalidSave)
        assertThat(archive.loadState()).isEqualTo(state(synced = true))
        archive.save(emptyMap())
        assertThat(archive.loadState()).isEqualTo(BipeChatArchiveStore.State(emptyMap(), emptySet()))
        archive.file.writeText("""{"version":1,"markers":{"!old:example.invalid":50}}""")
        assertThat(archive.loadState()).isEqualTo(BipeChatArchiveStore.State(mapOf("!old:example.invalid" to 50L), emptySet()))
    }

    @Test fun incomingTimestampMustBeNewerThanTheArchiveBaseline() {
        val input = listOf(100L to null, 100L to 100L, 100L to 99L, 100L to 101L, 0L to null, null to 100L)
        val result = input.map { (marker, incoming) -> BipeChatArchive.isArchived(marker, incoming) }
        assertThat(result).containsExactly(true, true, true, false, true, false).inOrder()
    }

    @Test fun accountFilesSurviveReopeningAndRejectedWrites() {
        val archive = store()
        archive.save(saved)
        assertThat(store().load()).isEqualTo(saved)
        assertThat(store("@b:example.invalid").load()).isEmpty()
        assertThat(archive.file.name).doesNotContain("@a")
        assertThrows(IOException::class.java) { archive.save(mapOf("!invalid" to -1L)) }
        assertThat(archive.load()).isEqualTo(saved)
        archive.save(emptyMap())
        assertThat(archive.load()).isEmpty()
        archive.file.writeText("corrupt")
        assertThrows(Exception::class.java) { archive.load() }
        assertThrows(Exception::class.java) { archive.save(emptyMap()) }
        assertThat(archive.file.readText()).isEqualTo("corrupt")
    }
}
