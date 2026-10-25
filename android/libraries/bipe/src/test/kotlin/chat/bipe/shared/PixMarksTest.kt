// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.pix.PixMarks
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File

/** test-vectors/pix-marks.json (ADR 0046, bipe-chat/bipe#130). iOS runs the same file. */
class PixMarksTest {
    private val vectors: JsonObject =
        Json.parseToJsonElement(File(File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors"), "pix-marks.json").readText()).jsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun lines() {
        val cases = vectors.getValue("lines").jsonArray.map { it.jsonObject }
        assertThat(cases).isNotEmpty()
        for (vector in cases) {
            val name = vector.str("name")
            val rawCard = vector.getValue("card").jsonObject
            val card = PixMarks.Card(rawCard.str("event_id")!!, rawCard.str("requester")!!, rawCard.getValue("payers").jsonArray.map { it.jsonPrimitive.content })
            val events = vector.getValue("events").jsonArray.map { it.jsonObject }.map {
                PixMarks.Event(it.str("event_id")!!, it.str("sender")!!, it.getValue("ts").jsonPrimitive.long, it["content"])
            }
            val expected = vector.getValue("expected").jsonObject
            val result = PixMarks.lines(card, events)
            assertWithMessage("$name rejected").that(result.rejected).isEqualTo(expected.getValue("rejected").jsonArray.map { it.jsonPrimitive.content })
            val lines = expected.getValue("lines").jsonArray.map { it.jsonObject }
            assertWithMessage("$name lines").that(result.lines.size).isEqualTo(lines.size)
            result.lines.zip(lines).forEach { (line, want) ->
                assertWithMessage("$name payer").that(line.payer).isEqualTo(want.str("payer"))
                assertWithMessage("$name state").that(line.state.raw).isEqualTo(want.str("state"))
                assertWithMessage("$name e2e_id").that(line.e2eId).isEqualTo(want.str("e2e_id"))
                assertWithMessage("$name paid").that(line.isPaid).isEqualTo(want.getValue("paid").jsonPrimitive.boolean)
                val history = (want.getValue("history") as JsonArray).map { "${it.jsonObject.str("event_id")} ${it.jsonObject.str("mark")}" }
                assertWithMessage("$name history").that(line.history.map { "${it.eventId} ${it.mark.raw}" }).isEqualTo(history)
            }
        }
    }

    @Test
    fun txid() {
        for (vector in vectors.getValue("txid").jsonArray.map { it.jsonObject }) {
            assertThat(PixMarks.txid(vector.str("request_id")!!)).isEqualTo(vector.str("expected"))
        }
    }

    @Test
    fun contentRoundTrip() {
        val content = PixMarks.content("\$card", PixMarks.Mark.SENT, null, "E12345678202610251200abcDEF12345")
        val card = PixMarks.Card("\$card", "@loja:x", listOf("@ana:x"))
        assertThat(PixMarks.lines(card, listOf(PixMarks.Event("\$m", "@ana:x", 1, content))).lines.single().state).isEqualTo(PixMarks.State.SENT)
        assertThat(PixMarks.content("\$card", PixMarks.Mark.RECEIVED, null, "E12345678202610251200abcDEF12345")).isNull()
        assertThat(PixMarks.content("\$card", PixMarks.Mark.SENT, null, "E1")).isNull()
    }
}
