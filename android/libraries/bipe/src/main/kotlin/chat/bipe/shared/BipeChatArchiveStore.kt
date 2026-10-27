// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Local baselines and migration metadata. Matrix owns the archive tag. */
class BipeChatArchiveStore(directory: File, accountId: String) {
    data class State(val markers: Map<String, Long>, val syncedRooms: Set<String>)
    private val owner = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val file = File(directory, "$owner.json")

    fun load(): Map<String, Long> = loadState().markers

    fun loadState(): State {
        if (!file.exists()) return State(emptyMap(), emptySet())
        val value = Json.parseToJsonElement(file.readText()).jsonObject
        if (value["version"]?.jsonPrimitive?.longOrNull != 1L) throw IOException("archive version")
        val markers = value.getValue("markers").jsonObject.mapValues { (_, v) ->
            v.jsonPrimitive.longOrNull ?: throw IOException("archive timestamp")
        }
        validate(markers)
        val synced = value["syncedRooms"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        if (!markers.keys.containsAll(synced)) throw IOException("archive migration metadata")
        return State(markers, synced)
    }

    fun save(markers: Map<String, Long>, syncedRooms: Set<String> = emptySet()) {
        validate(markers)
        if (!markers.keys.containsAll(syncedRooms)) throw IOException("archive migration metadata")
        loadState()
        val directory = file.parentFile ?: throw IOException("archive directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("archive directory")
        val temporary = File.createTempFile("archive-", ".tmp", directory)
        try {
            val content = JsonObject(mapOf(
                "version" to JsonPrimitive(1),
                "markers" to JsonObject(markers.mapValues { JsonPrimitive(it.value) }),
                "syncedRooms" to JsonArray(syncedRooms.sorted().map(::JsonPrimitive)),
            ))
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun validate(markers: Map<String, Long>) {
        if (markers.any { (room, time) -> room.isEmpty() || time < 0 || time > 9_007_199_254_740_991L }) throw IOException("archive metadata")
    }
}
