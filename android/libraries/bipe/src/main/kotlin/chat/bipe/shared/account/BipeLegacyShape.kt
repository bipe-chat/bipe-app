// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Structure-only descriptions for the legacy report (bipe-chat/bipe#603, spec account-start notes): names, sizes, value
 * types and JSON key names. Every function drops values. Matrix IDs show as their server name only.
 */
object BipeLegacyShape {
    /** The deepest JSON level the report describes. */
    const val MAX_DEPTH = 4

    private val plainName = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** A Matrix ID shows as `@…:server`; a name made of letters, digits, dots, dashes and underscores stays; else its length. */
    fun redact(text: String): String {
        if (text.startsWith("@") && text.contains(':')) return "@…:" + text.substringAfter(':')
        if (text.contains('@')) return "text(${text.length})"
        if (plainName.matches(text) && !looksRandom(text)) return text
        return "text(${text.length})"
    }

    /** The shape of a stored text: JSON key names and value types, else its length. */
    fun describe(text: String): JsonElement {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonObject || parsed is JsonArray) return buildJsonObject { put("json", shape(parsed, 0)) }
        return JsonPrimitive("text(${text.length})")
    }

    fun shape(element: JsonElement, depth: Int): JsonElement = when (element) {
        is JsonObject ->
            if (depth >= MAX_DEPTH) JsonPrimitive("object(${element.size})")
            else JsonObject(element.entries.associate { (key, value) -> redact(key) to shape(value, depth + 1) }.toSortedMap())
        is JsonArray ->
            if (depth >= MAX_DEPTH || element.isEmpty()) JsonPrimitive("array(${element.size})")
            else buildJsonObject {
                put("array", element.size)
                put("first", shape(element.first(), depth + 1))
            }
        is JsonNull -> JsonPrimitive("null")
        is JsonPrimitive -> JsonPrimitive(
            when {
                element.isString -> "string(${element.content.length})"
                element.booleanOrNull != null -> "bool"
                else -> "number"
            },
        )
    }

    /** The type of a SharedPreferences value, with the length of a text. Never the value. */
    fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is String -> "string(${value.length})"
        is Boolean -> "bool"
        is Int, is Long, is Float, is Double -> "number"
        is Set<*> -> "set(${value.size})"
        else -> "other"
    }

    /** A long run of letters and digits (a key, a token, a hash) shows as its length only. */
    private fun looksRandom(text: String): Boolean =
        text.length >= 20 && text.count(Char::isDigit) >= 4 && text.count(Char::isLetter) >= 4 && '.' !in text
}
