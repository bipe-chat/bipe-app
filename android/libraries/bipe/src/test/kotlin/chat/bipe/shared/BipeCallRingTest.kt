// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.calls.BipeCallLine
import chat.bipe.shared.calls.BipeCallRing
import chat.bipe.shared.calls.BipeCallRingRead
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File

/** bipe-shared test-vectors/calls/ringing.json (bipe-chat/bipe#759). iOS runs the same file in CallVectorTests.swift. */
class BipeCallRingTest {
    private val file: JsonObject = Json.parseToJsonElement(
        File(File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors"), "calls/ringing.json").readText(),
    ).jsonObject

    private fun list(key: String): List<JsonObject> = file.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun strings(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

    private fun normal(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { normal(it.value) }
        is JsonArray -> element.map { normal(it) }
        JsonNull -> null
        else -> element.jsonPrimitive.let { p -> if (p.isString) p.content else p.content.toDoubleOrNull() ?: p.content }
    }

    @Test
    fun ringContents() {
        val cases = list("ringContents")
        assertWithMessage("cases").that(cases).isNotEmpty()
        for (vector in cases) {
            val content = BipeCallRing.content(vector.text("notificationType"), vector.getValue("senderTs").jsonPrimitive.long,
                vector.text("membershipEventId"), strings(vector.getValue("userIds")), vector.getValue("video").jsonPrimitive.boolean)
            assertWithMessage(vector.text("name")).that(normal(content)).isEqualTo(normal(vector.getValue("content")))
        }
    }

    @Test
    fun ringReads() {
        for (vector in list("ringReads")) {
            val read = BipeCallRing.read(vector.getValue("content").jsonObject, vector.text("sender"),
                vector.getValue("originServerTs").jsonPrimitive.long, vector.text("ownUserId"), vector.getValue("now").jsonPrimitive.long)
            val expected = BipeCallRingRead(vector.getValue("ring").jsonPrimitive.boolean, vector.getValue("notify").jsonPrimitive.boolean,
                vector.getValue("video").jsonPrimitive.boolean, vector.getValue("expiresAt").jsonPrimitive.long)
            assertWithMessage(vector.text("name")).that(read).isEqualTo(expected)
        }
    }

    @Test
    fun lines() {
        for (vector in list("lines")) {
            val events = vector.getValue("memberEvents").jsonArray.map {
                val item = it.jsonObject
                item.text("userId") to item.getValue("ts").jsonPrimitive.long
            }
            val line = BipeCallRing.line(vector.getValue("isDm").jsonPrimitive.boolean, vector.text("sender"),
                vector.getValue("ringTs").jsonPrimitive.long, vector.getValue("active").jsonPrimitive.boolean,
                strings(vector.getValue("declinedBy")), events)
            val expected = BipeCallLine(BipeCallLine.Outcome.valueOf(vector.text("outcome").uppercase()),
                vector.getValue("minutes").jsonPrimitive.intOrNull, strings(vector.getValue("joined")))
            assertWithMessage(vector.text("name")).that(line).isEqualTo(expected)
        }
    }
}
