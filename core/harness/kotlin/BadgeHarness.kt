// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import chat.bipe.core.TrustedKey
import chat.bipe.core.boundBadgeResultCode
import chat.bipe.core.publicStatementResultCode
import chat.bipe.core.verifyBoundSenderBadge
import chat.bipe.core.verifyPublicIssuerStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/** bipe-shared test-vectors/badges.json through the packaged library, on Android and on the JVM (bipe-chat/bipe#1172). */
object BadgeHarness {
    /** Runs the vectors and returns the metrics line. Throws on the first wrong result. */
    fun run(json: String, betweenRuns: () -> Unit = {}): String {
        val file = Json.parseToJsonElement(json).jsonObject
        val now = file.getValue("now").jsonPrimitive.long
        val keys = file.getValue("publicKeys").jsonObject.map { (kid, key) ->
            TrustedKey(kid, Base64.getUrlDecoder().decode(key.jsonPrimitive.content))
        }
        val vectors = file.getValue("vectors").jsonArray.map { it.jsonObject }
        val bench = vectors.first { it.text("name") == "cnpj: valid" }
        val benchEnvelope = bench.getValue("envelope").toString()

        // The first call in this process, with the library load.
        val coldStart = System.nanoTime()
        val first = verifyBoundSenderBadge(benchEnvelope, bench.text("sender")!!, bench.text("senderMasterKey"), keys, now)
        val coldMicros = (System.nanoTime() - coldStart) / 1000.0
        check(boundBadgeResultCode(first) == "valid") { "bench vector" }

        for (vector in vectors) {
            val name = vector.text("name")
            val expected = vector.text("expect")!!
            val envelope = vector.getValue("envelope").toString()
            val bound = boundBadgeResultCode(verifyBoundSenderBadge(envelope, vector.text("sender")!!, vector.text("senderMasterKey"), keys, now))
            check(bound == expected) { "bound $name: $bound, expected $expected" }
            val publicExpected = if (expected == "subject_mismatch" || expected == "master_key_mismatch") "valid" else expected
            val statement = publicStatementResultCode(verifyPublicIssuerStatement(envelope, "bipe.net.br", keys, now))
            check(statement == publicExpected) { "public $name: $statement, expected $publicExpected" }
        }

        betweenRuns()
        val samples = DoubleArray(1000) {
            val start = System.nanoTime()
            verifyBoundSenderBadge(benchEnvelope, bench.text("sender")!!, bench.text("senderMasterKey"), keys, now)
            (System.nanoTime() - start) / 1000.0
        }.sorted()
        return "BIPE_CORE_METRICS {\"vectors\":${vectors.size},\"cold_us\":$coldMicros,\"p95_us\":${samples[949]}}"
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}
