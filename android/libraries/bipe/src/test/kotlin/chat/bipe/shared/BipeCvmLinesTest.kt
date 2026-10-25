// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.company.BipeCvmLines
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** bipe-shared test-vectors/cvm-lines.json (ADR 0077, bipe-chat/bipe#238). */
class BipeCvmLinesTest {
    private val vectorsDir = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors")

    @Test fun sharedVectors() {
        val vectors = Json.parseToJsonElement(File(vectorsDir, "cvm-lines.json").readText()).jsonObject.getValue("vectors") as JsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val result = BipeCvmLines.lines(vector["cvm"])
            val expected = (vector.getValue("lines") as JsonArray).map { it.jsonObject }
            assertEquals("keys $name", expected.map { it.getValue("key").jsonPrimitive.content }, result.lines.map { it.key.key })
            assertEquals("args $name", expected.map { it["arg"]?.takeUnless { a -> a is JsonNull }?.jsonPrimitive?.content }, result.lines.map { it.arg })
            assertEquals("danger $name", vector.getValue("danger").jsonPrimitive.boolean, result.danger)
        }
    }
}
