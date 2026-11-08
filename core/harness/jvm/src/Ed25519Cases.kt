// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import chat.bipe.core.TrustedKey
import chat.bipe.core.publicStatementResultCode
import chat.bipe.core.verifyPublicIssuerStatement
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * core/harness/ed25519-cases.json (bipe-chat/bipe#1277 item 7): bipe-core accepts no signature that Tink refuses.
 * The payload of a case is not JSON, so `malformed` means that the signature check passed.
 */
object Ed25519Cases {
    /** Returns the result line. Throws when bipe-core accepts a case that Tink refuses. */
    fun run(json: String): String {
        val cases = Json.parseToJsonElement(json).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        check(cases.isNotEmpty()) { "no cases" }
        val rows = cases.mapIndexed { index, case ->
            val (message, key, signature) = listOf("message", "publicKey", "signature").map { hex(case.getValue(it).jsonPrimitive.content) }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val envelope = """{"v":1,"kid":"case","payload":"${encoder.encodeToString(message)}","sig":"${encoder.encodeToString(signature)}"}"""
            val core = publicStatementResultCode(verifyPublicIssuerStatement(envelope, "bipe.net.br", listOf(TrustedKey("case", key)), 1_800_000_000L)) == "malformed"
            val tink = runCatching { Ed25519Verify(key).verify(signature, message) }.isSuccess
            check(!core || tink) { "case $index: bipe-core accepts a signature that Tink refuses" }
            "$index:${if (core) "V" else "X"}${if (tink) "V" else "X"}"
        }
        return "BIPE_ED25519_CASES core/Tink ${rows.joinToString(" ")}"
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
