// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.bipe.shared.BadgeVerificationResult
import chat.bipe.shared.BadgeVerifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.sdkGitSha

/** The AAR on a device, with libmatrix_sdk_ffi.so loaded in the same process (bipe-chat/bipe#1172). */
@RunWith(AndroidJUnit4::class)
class BadgeHarnessTest {
    @Test
    fun vectorsMetricsAndMatrixSdk() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("badges.json").bufferedReader().use { it.readText() }
        // Between the two runs of the core: load the SDK library and call it.
        val metrics = BadgeHarness.run(json) { assertTrue(sdkGitSha().isNotEmpty()) }
        println(metrics)
        println(currentVerifierMetrics(json))
    }

    @Test
    fun issuerAndTheftVectors() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open(name).bufferedReader().use { it.readText() }
        println(IssuerTheftHarness.run(read("badges.json"), read("badge-issuers.json"), read("theft-marks.json")))
    }

    /** BadgeVerifier.kt with Tink, on the same vector, device and build type. It runs second, so its cold time is not comparable. */
    private fun currentVerifierMetrics(json: String): String {
        val file = Json.parseToJsonElement(json).jsonObject
        val nowMillis = file.getValue("now").jsonPrimitive.long * 1000
        val bench = file.getValue("vectors").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "cnpj: valid" }
        val envelope = bench.getValue("envelope").toString()
        val sender = bench.getValue("sender").jsonPrimitive.content
        val masterKey = bench.getValue("senderMasterKey").jsonPrimitive.content
        val keys = file.getValue("publicKeys").jsonObject.mapValues { (_, key) -> java.util.Base64.getUrlDecoder().decode(key.jsonPrimitive.content) }
        val verifier = BadgeVerifier(keys)
        val coldStart = System.nanoTime()
        check(verifier.verify(envelope, sender, masterKey, nowMillis) is BadgeVerificationResult.Valid)
        val coldMicros = (System.nanoTime() - coldStart) / 1000.0
        val samples = DoubleArray(1000) {
            val start = System.nanoTime()
            verifier.verify(envelope, sender, masterKey, nowMillis)
            (System.nanoTime() - start) / 1000.0
        }.sorted()
        return "BIPE_BASELINE_METRICS {\"cold_us\":$coldMicros,\"p95_us\":${samples[949]}}"
    }
}
