// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The current verifier's time, for comparison with bipe-core (bipe-chat/bipe#1172). Runs only with BIPE_BENCH set. */
class BadgeVerifierBenchTest {
    @Test
    fun coldStartAndP95() {
        assumeTrue(System.getenv("BIPE_BENCH") != null)
        val file = Json.parseToJsonElement(File(System.getenv("BIPE_SHARED"), "test-vectors/badges.json").readText()).jsonObject
        val nowMillis = file.getValue("now").jsonPrimitive.long * 1000
        val bench = file.getValue("vectors").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "cnpj: valid" }
        val envelope = bench.getValue("envelope").toString()
        val sender = bench.getValue("sender").jsonPrimitive.content
        val masterKey = bench.getValue("senderMasterKey").jsonPrimitive.content

        val coldStart = System.nanoTime()
        val verifier = BadgeVerifier(file.getValue("publicKeys").jsonObject.mapValues { (_, key) -> Base64Url.decode(key.jsonPrimitive.content)!! })
        check(verifier.verify(envelope, sender, masterKey, nowMillis) is BadgeVerificationResult.Valid)
        val coldMicros = (System.nanoTime() - coldStart) / 1000.0
        val samples = DoubleArray(1000) {
            val start = System.nanoTime()
            verifier.verify(envelope, sender, masterKey, nowMillis)
            (System.nanoTime() - start) / 1000.0
        }.sorted()
        File(System.getenv("BIPE_BENCH")).writeText("BIPE_BASELINE_METRICS {\"cold_us\":$coldMicros,\"p95_us\":${samples[949]}}\n")
    }
}
