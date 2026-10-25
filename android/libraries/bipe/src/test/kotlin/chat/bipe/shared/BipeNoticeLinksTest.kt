// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** bipe-shared test-vectors/notice-links.json (ADR 0036, bipe-chat/bipe#245). */
class BipeNoticeLinksTest {
    private val vectorsDir = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors")

    @Test fun sharedVectors() {
        val file = Json.parseToJsonElement(File(vectorsDir, "notice-links.json").readText()).jsonObject
        val domains = (file.getValue("domains") as JsonArray).map { it.jsonPrimitive.content }
        val vectors = file.getValue("vectors") as JsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val url = vector.getValue("url").jsonPrimitive.content
            assertEquals(vector.getValue("name").jsonPrimitive.content, vector.getValue("clickable").jsonPrimitive.boolean, BipeNoticeLinks.isClickable(url, domains))
        }
    }
}
