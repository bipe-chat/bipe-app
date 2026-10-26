// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.calls.BipeCall
import chat.bipe.shared.calls.BipeCallKey
import chat.bipe.shared.calls.BipeCallMember
import chat.bipe.shared.calls.BipeCallOpenId
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File

/** bipe-shared test-vectors/calls/matrixrtc.json (bipe-chat/bipe#758). iOS runs the same file in CallVectorTests.swift. */
class BipeCallTest {
    private val file: JsonObject = Json.parseToJsonElement(
        File(File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors"), "calls/matrixrtc.json").readText(),
    ).jsonObject

    private fun list(key: String): List<JsonObject> = file.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Numbers compare by value: 3600 and 3600.0 are the same JSON number. */
    private fun normal(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { normal(it.value) }
        is JsonArray -> element.map { normal(it) }
        JsonNull -> null
        else -> element.jsonPrimitive.let { p -> if (p.isString) p.content else p.content.toDoubleOrNull() ?: p.content }
    }

    @Test
    fun stateKeys() {
        val cases = list("stateKeys")
        assertWithMessage("cases").that(cases).isNotEmpty()
        for (vector in cases) {
            assertWithMessage("state key").that(BipeCall.stateKey(vector.text("userId"), vector.text("deviceId"))).isEqualTo(vector.text("stateKey"))
            assertWithMessage("identity").that(BipeCall.livekitIdentity(vector.text("userId"), vector.text("deviceId")))
                .isEqualTo(vector.text("livekitIdentity"))
        }
    }

    @Test
    fun memberships() {
        for (vector in list("memberships")) {
            val content = BipeCall.membershipContent(vector.text("deviceId"), vector.text("roomId"), vector.text("focusUrl"),
                vector.getValue("createdTs").jsonPrimitive.long, vector.getValue("video").jsonPrimitive.boolean)
            assertWithMessage(vector.text("name")).that(normal(content)).isEqualTo(normal(vector.getValue("content")))
        }
    }

    @Test
    fun sfuRequests() {
        for (vector in list("sfuRequests")) {
            val raw = vector.getValue("openId").jsonObject
            val openId = BipeCallOpenId(raw.text("accessToken"), raw.text("tokenType"), raw.text("matrixServerName"),
                raw.getValue("expiresInSeconds").jsonPrimitive.long)
            val body = BipeCall.sfuRequestBody(vector.text("roomId"), vector.text("deviceId"), openId)
            assertWithMessage("body").that(normal(body)).isEqualTo(normal(vector.getValue("body")))
            assertWithMessage("answer").that(BipeCall.sfuAnswer(vector.getValue("response").jsonObject)?.first).isEqualTo("wss://rtc.bipe.net.br")
        }
    }

    @Test
    fun keyEvents() {
        for (vector in list("keyEvents")) {
            val content = BipeCall.keyEventContent(vector.getValue("index").jsonPrimitive.int, hex(vector.text("keyHex")),
                vector.text("deviceId"), vector.getValue("sentTs").jsonPrimitive.long)
            assertWithMessage(vector.text("name")).that(normal(content)).isEqualTo(normal(vector.getValue("content")))
        }
        for (vector in list("keyReads")) {
            val keys = BipeCall.readKeys(vector.text("sender"), vector.getValue("content").jsonObject)
            val expected = vector.getValue("keys").jsonArray.map {
                val item = it.jsonObject
                BipeCallKey(item.text("identity"), item.getValue("index").jsonPrimitive.int, hex(item.text("keyHex")))
            }
            assertWithMessage(vector.text("name")).that(keys).isEqualTo(expected)
        }
    }

    @Test
    fun foci() {
        for (vector in list("foci")) {
            assertWithMessage(vector.text("name")).that(BipeCall.focusUrl(vector.getValue("wellKnown").jsonObject))
                .isEqualTo(vector.getValue("focusUrl").jsonPrimitive.contentOrNull)
        }
    }

    @Test
    fun members() {
        val members = file.getValue("members").jsonObject
        val now = members.getValue("now").jsonPrimitive.long
        val active = mutableListOf<BipeCallMember>()
        for (vector in members.getValue("events").jsonArray.map { it.jsonObject }) {
            val member = BipeCall.member(vector.text("sender"), vector.text("stateKey"), vector.getValue("originServerTs").jsonPrimitive.long,
                vector.getValue("content").jsonObject, now)
            val expected = (vector.getValue("member") as? JsonObject)?.let {
                BipeCallMember(it.text("userId"), it.text("deviceId"), it.getValue("createdTs").jsonPrimitive.long,
                    it["focusUrl"]?.jsonPrimitive?.contentOrNull)
            }
            assertWithMessage(vector.text("name")).that(member).isEqualTo(expected)
            member?.let(active::add)
        }
        val oldest = members.getValue("oldestFocus").jsonObject
        assertWithMessage("oldest").that(BipeCall.oldestFocus(active, oldest.text("own"))).isEqualTo(oldest.text("focusUrl"))
        assertWithMessage("alone").that(BipeCall.oldestFocus(emptyList(), oldest.text("own"))).isEqualTo(oldest.text("alone"))
    }
}
