// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.safety

import chat.bipe.core.scamMatchIds
import chat.bipe.core.ScamKind
import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Tests the actual generated Kotlin -> shared Rust DLL boundary. Authored for Windows CI, not run locally. */
class RustSafetyVectorTest {
    @Before fun nativeWindows() { assumeTrue("Windows core DLL required", Platform.isWindows()) }

    @Test fun everyIndependentDetectionVectorUsesTheRustMatcher() {
        val vectors = vectors("scam-detection.json")
        assertTrue(vectors.isNotEmpty())
        for ((index, value) in vectors.withIndex()) {
            val row = value.jsonObject
            assertEquals("detection vector $index", row.getValue("matches").jsonArray.map { it.jsonPrimitive.content },
                         scamMatchIds(row.getValue("message").jsonPrimitive.content))
        }
    }

    @Test fun everyIndependentWarningVectorPassesThroughTheDesktopAdapter() {
        val vectors = vectors("scam-warnings.json")
        assertTrue(vectors.isNotEmpty())
        for ((index, value) in vectors.withIndex()) {
            val row = value.jsonObject
            val result = CoreSafety.warning(row.getValue("message").jsonPrimitive.content)
            val expected = row.getValue("kind").takeUnless { it == JsonNull }?.jsonPrimitive?.content
            if (expected == null) assertEquals("warning vector $index", MessageSafety.Clear, result)
            else {
                assertTrue("warning vector $index", result is MessageSafety.Warning)
                assertEquals("warning vector $index", expected, (result as MessageSafety.Warning).kind.registryId())
            }
        }
    }

    @Test fun eachBodyIsAssessedWithoutASharedEventCache() {
        // No global event-id/hash cache. Actual account/room switching remains a native integration acceptance.
        val first = CoreSafety.warning("Faz um pix pra mim? É urgente. Chave 123.456.789-09")
        assertEquals(MessageSafety.Warning(ScamKind.PIX_KEY), first)
        assertEquals(MessageSafety.Clear, CoreSafety.warning("Bom dia! A reunião do condomínio é às 19h"))
        assertEquals(first, CoreSafety.warning("Faz um pix pra mim? É urgente. Chave 123.456.789-09"))
    }

    private fun vectors(file: String): JsonArray = Json.parseToJsonElement(
        Files.readString(Path.of(System.getenv("BIPE_SHARED") ?: error("BIPE_SHARED required"), "test-vectors", file)),
    ).jsonObject.getValue("vectors").jsonArray
}
