// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Test

class MessageTextTest {
    private val vectors = Json.parseToJsonElement(File(
        System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors/message-formatting.json",
    ).readText()).jsonObject

    @Test fun sharedFormattingCases() {
        for (item in vectors.getValue("cases").jsonArray) {
            val v = item.jsonObject
            fun string(key: String) = v.getValue(key).jsonPrimitive.content
            val parsed = BipeMessageText.parse(string("body"), v["html"]?.jsonPrimitive?.content)
            assertWithMessage(string("name")).that(parsed.text).isEqualTo(string("text"))
            assertThat(parsed.runs.mapNotNull { it.link }.toSet()).isEqualTo(v.getValue("links").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertThat(parsed.runs.filter { it.bold }.joinToString("") { it.text }).isEqualTo(string("bold"))
            assertThat(parsed.runs.filter { it.italic }.joinToString("") { it.text }).isEqualTo(string("italic"))
            assertThat(parsed.runs.filter { it.strike }.joinToString("") { it.text }).isEqualTo(string("strike"))
            assertThat(parsed.runs.filter { it.code }.joinToString("") { it.text }).isEqualTo(string("code"))
            for (link in parsed.runs.mapNotNull { it.link }) assertThat(parsed.safetyText).contains(link)
        }
    }
    @Test fun safeLinkSchemes() {
        for (item in vectors.getValue("safeLinks").jsonArray) {
            val v = item.jsonObject
            assertThat(BipeMessageText.safeLink(v.getValue("url").jsonPrimitive.content) != null)
                .isEqualTo(v.getValue("allowed").jsonPrimitive.boolean)
        }
    }
    @Test fun parserLimitsFallBackToBody() {
        assertThat(BipeMessageText.parse("fallback", "x".repeat(131073)).text).isEqualTo("fallback")
        assertThat(BipeMessageText.parse("fallback", "<div>".repeat(70) + "deep" + "</div>".repeat(70)).text).isEqualTo("fallback")
    }
}
