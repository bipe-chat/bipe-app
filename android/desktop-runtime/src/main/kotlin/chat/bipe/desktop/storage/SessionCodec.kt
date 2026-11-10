// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/** Private Windows disk envelope. Only DPAPI-protected bytes are written to a file. */
internal object SessionCodec {
    fun encode(slot: AccountSlot, record: StoredRecord): ByteArray = buildJsonObject {
        put("version", 1)
        put("slot", slot.value)
        put("phase", when (record) {
            is StoredRecord.Linked -> "linked"
            is StoredRecord.Pending -> "pending"
            is StoredRecord.Discarding -> "discarding"
        })
        if (record is StoredRecord.Discarding) put("remoteEnded", record.remoteEnded)
        put("session", record.session?.let { session ->
            buildJsonObject {
                put("accessToken", session.accessToken)
                put("refreshToken", session.refreshToken?.let(::JsonPrimitive) ?: JsonNull)
                put("userId", session.userId)
                put("deviceId", session.deviceId)
                put("homeserverUrl", session.homeserverUrl)
                put("oauthData", session.oauthData?.let(::JsonPrimitive) ?: JsonNull)
                put("slidingSyncVersion", session.slidingSyncVersion.name)
            }
        } ?: JsonNull)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(slot: AccountSlot, bytes: ByteArray): StoredRecord {
        try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (root["version"] != JsonPrimitive(1) || root.text("slot") != slot.value) corrupt()
            val value = root.getValue("session")
            val session = if (value == JsonNull) null else value.jsonObject.let { item ->
                Session(
                    accessToken = item.text("accessToken").also { if (it.isBlank()) corrupt() },
                    refreshToken = item.optionalText("refreshToken"),
                    userId = item.text("userId").also { if (it.isBlank()) corrupt() },
                    deviceId = item.text("deviceId").also { if (it.isBlank()) corrupt() },
                    homeserverUrl = item.text("homeserverUrl"),
                    oauthData = item.optionalText("oauthData"),
                    slidingSyncVersion = when (item.text("slidingSyncVersion")) {
                        "NATIVE" -> SlidingSyncVersion.NATIVE
                        "NONE" -> SlidingSyncVersion.NONE
                        else -> corrupt()
                    },
                )
            }
            return when (root.text("phase")) {
                "pending" -> StoredRecord.Pending(session)
                "linked" -> StoredRecord.Linked(session ?: corrupt())
                "discarding" -> {
                    if (session != null) corrupt()
                    StoredRecord.Discarding(when (root["remoteEnded"]) {
                        JsonPrimitive(true) -> true
                        JsonPrimitive(false) -> false
                        else -> corrupt()
                    })
                }
                else -> corrupt()
            }
        } catch (failure: StorageFailure) { throw failure }
        catch (_: Exception) {
            // Parser errors can quote the credential input. Do not attach the cause or message.
            corrupt()
        }
    }

    private fun JsonObject.text(key: String): String = (getValue(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: corrupt()

    private fun JsonObject.optionalText(key: String): String? =
        if (getValue(key) == JsonNull) null else text(key)

    private fun corrupt(): Nothing = throw StorageFailure(StorageCode.CORRUPT_RECORD)
}
