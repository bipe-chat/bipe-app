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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Local archive metadata only. Call serially, in the account's model, and publish only after save succeeds. */
class BipeChatArchiveStore(directory: File, accountId: String) {
    private val owner = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val file = File(directory, "$owner.json")

    fun load(): Map<String, Long> {
        if (!file.exists()) return emptyMap()
        val value = Json.parseToJsonElement(file.readText()).jsonObject
        if (value["version"]?.jsonPrimitive?.longOrNull != 1L) throw IOException("archive version")
        val markers = value.getValue("markers").jsonObject.mapValues { (_, v) ->
            v.jsonPrimitive.longOrNull ?: throw IOException("archive timestamp")
        }
        validate(markers)
        return markers
    }

    fun save(markers: Map<String, Long>) {
        validate(markers)
        val directory = file.parentFile ?: throw IOException("archive directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("archive directory")
        val temporary = File.createTempFile("archive-", ".tmp", directory)
        try {
            val content = JsonObject(mapOf("version" to JsonPrimitive(1), "markers" to JsonObject(markers.mapValues { JsonPrimitive(it.value) })))
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
