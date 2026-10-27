// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeChatArchive
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatArchiveTest {
    @get:Rule val disk = TemporaryFolder()
    private fun store(owner: String = "@a:example.invalid") = BipeChatArchiveStore(disk.root, owner)

    @Test fun sharedArchiveContracts() {
        val root = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared")
        val legacy = Json.parseToJsonElement(File(root, "test-vectors/chat-archive.json").readText())
        for (item in legacy.jsonObject.getValue("vectors").jsonArray) {
            val v = item.jsonObject
            val result = BipeChatArchive.isArchived(v.getValue("marker").jsonPrimitive.longOrNull,
                v.getValue("incoming").jsonPrimitive.longOrNull)
            check(result == v.getValue("expected").jsonPrimitive.boolean) { v.getValue("name").toString() }
        }
        val sync = Json.parseToJsonElement(File(root, "test-vectors/archive-sync.json").readText())
        for (item in sync.jsonObject.getValue("cases").jsonArray) {
            val v = item.jsonObject
            val expected = v.getValue("expected").jsonObject
            val result = BipeChatArchive.reconcile(
                v.getValue("marker").jsonPrimitive.longOrNull,
                v.getValue("synced").jsonPrimitive.boolean,
                v.getValue("serverArchived").jsonPrimitive.boolean,
                v.getValue("activity").jsonPrimitive.longOrNull,
                v.getValue("incoming").jsonPrimitive.longOrNull,
            )
            val actual = listOf(result.marker, result.synced, result.tagWrite)
            val wanted = listOf(expected.getValue("marker").jsonPrimitive.longOrNull,
                expected.getValue("synced").jsonPrimitive.boolean,
                expected.getValue("tagWrite").jsonPrimitive.booleanOrNull)
            check(actual == wanted) { "${v.getValue("name")}: expected $wanted, got $actual" }
        }
    }

    @Test fun snapshotPropertiesAcrossMigrationStatesAndRejectedWrites() {
        val archive = store()
        val other = store("@b:example.invalid")
        val otherState = BipeChatArchiveStore.State(mapOf("!other:example.invalid" to 22L), emptySet())
        other.save(otherState.markers, otherState.syncedRooms)
        check(archive.file != other.file && !archive.file.name.contains("@a"))
        val limits = listOf(0L, 100L, 9_007_199_254_740_991L)
        val rejected = listOf(
            BipeChatArchiveStore.State(mapOf("!bad" to -1L), emptySet()),
            BipeChatArchiveStore.State(mapOf("!bad" to 9_007_199_254_740_992L), emptySet()),
            BipeChatArchiveStore.State(emptyMap(), setOf("!orphan")),
        )
        // Each subset of up to three markers can be migrated independently. Include clearing to empty.
        for (count in 3 downTo 0) {
            val markers = (0 until count).associate { "!room$it:example.invalid" to limits[it] }
            for (mask in 0 until (1 shl count)) {
                val synced = markers.keys.filterIndexed { index, _ -> (mask and (1 shl index)) != 0 }.toSet()
                val expected = BipeChatArchiveStore.State(markers, synced)
                archive.save(markers, synced)
                check(store().loadState() == expected && archive.load() == markers) { "round trip count=$count mask=$mask" }
                check(other.loadState() == otherState) { "write crossed account boundary" }
                val bytes = archive.file.readBytes()
                for (invalid in rejected) {
                    val error = runCatching { archive.save(invalid.markers, invalid.syncedRooms) }.exceptionOrNull()
                    check(error is IOException) { "invalid snapshot accepted: $invalid" }
                    check(bytes.contentEquals(archive.file.readBytes()) && store().loadState() == expected) {
                        "rejected write replaced snapshot count=$count mask=$mask"
                    }
                }
            }
        }
        archive.file.writeText("""{"version":1,"markers":{"!old:example.invalid":50}}""")
        val legacy = archive.loadState()
        check(legacy.markers == mapOf("!old:example.invalid" to 50L) && legacy.syncedRooms.isEmpty())
        val corruptSnapshots = listOf(
            "corrupt",
            """{"version":2,"markers":{}}""",
            """{"version":1,"markers":[]}""",
            """{"version":1,"markers":{},"syncedRooms":["!orphan"]}""",
        )
        for ((index, corrupt) in corruptSnapshots.withIndex()) {
            archive.file.writeText(corrupt)
            val read = runCatching { archive.loadState() }
            val write = runCatching { archive.save(emptyMap()) }
            check(read.isFailure && write.isFailure && archive.file.readText() == corrupt) { "corrupt case $index replaced" }
        }
    }
}
