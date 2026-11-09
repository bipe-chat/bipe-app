// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import chat.bipe.core.TheftMarkResult
import chat.bipe.core.TrustedKey
import chat.bipe.core.boundBadgeResultCode
import chat.bipe.core.issuedBadgeResultCode
import chat.bipe.core.theftMarkResultCode
import chat.bipe.core.verifyBoundSenderBadge
import chat.bipe.core.verifyIssuedBadge
import chat.bipe.core.verifyTheftMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/**
 * bipe-shared test-vectors/badge-issuers.json and theft-marks.json through the packaged library, on Android and on
 * the JVM. The crate tests (core/crates/bipe-core/tests) run the same files against the source.
 */
object IssuerTheftHarness {
    /** Returns the count line. Throws on the first wrong result. */
    fun run(badgesJson: String, issuersJson: String, theftJson: String): String {
        val issuers = Json.parseToJsonElement(issuersJson).jsonObject
        val now = issuers.getValue("now").jsonPrimitive.long
        val lifeDays = issuers.getValue("lifeDays").jsonPrimitive.int.toUInt()
        val registry = buildJsonObject {
            put("issuers", issuers.getValue("trustedIssuers"))
            put("schemes", issuers.getValue("schemes"))
            put("regions", issuers.getValue("regions"))
        }.toString()
        val issued = issuers.getValue("vectors").jsonArray.map { it.jsonObject }
        check(issued.isNotEmpty()) { "no issuer vectors" }
        for (vector in issued) {
            val result = verifyIssuedBadge(
                vector.getValue("envelope").toString(), vector.text("sender")!!, vector.text("senderMasterKey"), registry, lifeDays, now,
            )
            val code = issuedBadgeResultCode(result)
            check(code == vector.text("expect")) { "issued ${vector.text("name")}: $code, expected ${vector.text("expect")}" }
        }

        // A verifier from before ADR 0272: the flat keys of badges.json, and no person or org level.
        val flatKeys = keys(Json.parseToJsonElement(badgesJson).jsonObject)
        val flat = issuers.getValue("verifierWithoutSchemes").jsonArray.map { it.jsonObject }
        for (vector in flat) {
            val code = boundBadgeResultCode(
                verifyBoundSenderBadge(vector.getValue("envelope").toString(), vector.text("sender")!!, vector.text("senderMasterKey"), flatKeys, now),
            )
            check(code == vector.text("expect")) { "flat ${vector.text("name")}: $code, expected ${vector.text("expect")}" }
        }

        val theft = Json.parseToJsonElement(theftJson).jsonObject
        val theftKeys = keys(theft)
        val theftNow = theft.getValue("now").jsonPrimitive.long
        val marks = theft.getValue("vectors").jsonArray.map { it.jsonObject }
        check(marks.isNotEmpty()) { "no theft-mark vectors" }
        for (vector in marks) {
            val name = vector.text("name")
            val subject = vector.text("subject")!!
            val expected = vector.getValue("expected").jsonObject
            val result = verifyTheftMark(vector.getValue("envelope").toString(), subject, theftKeys, theftNow)
            val error = expected.text("error")
            if (error == null) {
                check(result is TheftMarkResult.Valid) { "theft $name: ${theftMarkResultCode(result)}, expected valid" }
                check(result.statement.sub == subject) { "theft $name: subject" }
                check(result.statement.stolenAt == expected.getValue("stolenAt").jsonPrimitive.long) { "theft $name: stolenAt" }
            } else {
                val code = theftMarkResultCode(result)
                check(code == error) { "theft $name: $code, expected $error" }
            }
        }
        return "BIPE_CORE_VECTORS {\"issued\":${issued.size},\"flat\":${flat.size},\"theft\":${marks.size}}"
    }

    private fun keys(file: JsonObject): List<TrustedKey> = file.getValue("publicKeys").jsonObject.map { (kid, key) ->
        TrustedKey(kid, Base64.getUrlDecoder().decode(key.jsonPrimitive.content))
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}
