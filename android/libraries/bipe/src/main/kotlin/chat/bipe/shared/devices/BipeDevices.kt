// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.devices

import android.annotation.SuppressLint
import android.content.Context
import chat.bipe.core.AccountDevice
import chat.bipe.core.AccountDeviceEntry
import chat.bipe.core.DeviceInfoResult
import chat.bipe.core.deviceInfoFromEvent
import chat.bipe.core.deviceRows
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.weblink.BipeMatrixDevice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import chat.bipe.core.userAgentInfo as coreUserAgentInfo

/**
 * Aparelhos e assistentes (bipe ADR 0291, bipe-chat/bipe#1481; registries/device-kinds.json, bipe-shared
 * docs/drafts-room.md "Device entries"). Wrappers over the bipe-core rules device_info_from_event, user_agent_info and
 * device_rows, which match deviceInfoFromEvent, userAgentInfo and deviceRows in bipe-shared tools/lib/registries.mjs.
 */
object BipeDevices {
    /** A valid device entry of the drafts room. [ts] is origin_server_ts, 0 for an entry that the app writes. */
    data class Entry(
        val deviceId: String,
        /** "self" or "approval". */
        val source: String,
        /** A registry kind key other than "unknown". */
        val kind: String,
        val model: String?,
        val os: String?,
        val app: String?,
        /** Milliseconds. */
        val linkedAt: Long?,
        val linkedBy: String?,
        val attested: Boolean,
        val ts: Long = 0,
    )

    /** What a user agent says about a device. */
    data class UserAgent(val kind: String, val app: String?, val model: String?, val os: String?)

    /** Type DeviceRow of spec linked-devices, without the place (GET /v1/web-link/place). */
    data class Row(
        val deviceId: String,
        val isThis: Boolean,
        val kind: String,
        /** A PhosphorIcon id. */
        val icon: String,
        /** A bipe-shared string key. */
        val label: String,
        /** "devices" or "programs". */
        val section: String,
        val leavesBrazil: Boolean,
        val name: String?,
        val model: String?,
        val os: String?,
        val app: String?,
        val lastSeen: Long?,
        val ip: String?,
        val linkedAt: Long?,
        /** The device ID of the approving device. */
        val linkedBy: String?,
        val attested: Boolean,
        val warning: Boolean,
        val canSignOut: Boolean,
    ) {
        /** The kind label, for example "Navegador". The key is a bipe-shared string of this module. */
        @SuppressLint("DiscouragedApi")
        fun kindLabel(context: Context): String {
            val id = context.resources.getIdentifier(label, "string", context.packageName)
            return if (id != 0) context.getString(id) else context.getString(R.string.bipe_device_kind_unknown)
        }

        /** The row title: the name, else bipe_linked_devices_unknown_name. */
        fun title(context: Context): String = name ?: context.getString(R.string.bipe_linked_devices_unknown_name)
    }

    data class Counts(val devices: Int, val programs: Int, val unknown: Int) {
        /**
         * The subtitle of the group row: bipe_devices_summary, or bipe_devices_summary_unknown when an unknown device
         * exists and the registry flag unknownWarning is on.
         */
        fun summary(context: Context): String =
            if (unknown > 0 && BipeRegistries.DEVICE_UNKNOWN_WARNING) {
                context.getString(R.string.bipe_devices_summary_unknown, unknown.toString())
            } else {
                context.getString(R.string.bipe_devices_summary, devices.toString(), programs.toString())
            }
    }

    /** The rows (this device first, then newest use first) and the counts. */
    data class DeviceList(val rows: List<Row>, val counts: Counts)

    /**
     * device_info_from_event for the raw JSON of a drafts room event. [sender] (EventTimelineItem.sender) replaces the
     * JSON sender when given, [redacted] marks a redacted timeline item, and [senderVerified] is false when the SDK shows
     * a shield on the event. Null when the event is not a valid entry.
     */
    fun entry(json: String?, sender: String? = null, redacted: Boolean = false, senderVerified: Boolean, ownUserId: String): Entry? {
        val event = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val unsigned = event["unsigned"] as? JsonObject
        val isRedacted = redacted || unsigned?.get("redacted_because") != null
        val patched = JsonObject(
            event.toMutableMap<String, JsonElement>().apply {
                sender?.let { put("sender", JsonPrimitive(it)) }
                if (isRedacted) put("redacted", JsonPrimitive(true))
            },
        )
        val result = deviceInfoFromEvent(patched.toString(), ownUserId, senderVerified) as? DeviceInfoResult.Entry ?: return null
        val e = result.entry
        return Entry(e.deviceId, e.source, e.kind, e.model, e.os, e.app, e.linkedAt, e.linkedBy, e.attested, e.ts)
    }

    /** user_agent_info: what a user agent says about a device, null when no rule matches. */
    fun userAgentInfo(userAgent: String): UserAgent? =
        coreUserAgentInfo(userAgent)?.let { UserAgent(it.kind, it.app, it.model, it.os) }

    /** device_rows: the rows of Aparelhos e assistentes from GET /_matrix/client/v3/devices and the valid entries. */
    fun rows(thisDeviceId: String, devices: List<BipeMatrixDevice>, entries: List<Entry>): DeviceList {
        val list = deviceRows(
            thisDeviceId,
            devices.map { AccountDevice(it.deviceId, it.displayName, it.lastSeenTs, it.lastSeenIp) },
            entries.map { AccountDeviceEntry(it.deviceId, it.source, it.kind, it.model, it.os, it.app, it.linkedAt, it.linkedBy, it.attested, it.ts) },
        )
        val rows = list.rows.map { r ->
            Row(
                r.deviceId, r.isThis, r.kind, r.icon, r.label, r.section, r.leavesBrazil, r.name, r.model, r.os, r.app,
                r.lastSeen, r.ip, r.linkedAt, r.linkedBy, r.attested, r.warning, r.canSignOut,
            )
        }
        return DeviceList(rows, Counts(list.counts.devices.toInt(), list.counts.programs.toInt(), list.counts.unknown.toInt()))
    }

    /**
     * The content of a device entry for Room.sendRaw with event type m.room.message: an m.notice whose body other Matrix
     * apps show, and the field chat.bipe.device_info. Empty optional fields are left out.
     */
    fun content(entry: Entry): String {
        val body = listOfNotNull(entry.model ?: entry.app, entry.os).joinToString(" · ").ifEmpty { entry.kind }
        return buildJsonObject {
            put("msgtype", BipeRegistries.DEVICE_INFO_MSGTYPE)
            put("body", body)
            put(
                BipeRegistries.DEVICE_INFO_FIELD,
                buildJsonObject {
                    put("device_id", entry.deviceId)
                    put("source", entry.source)
                    put("kind", entry.kind)
                    entry.model?.let { put("model", it) }
                    entry.os?.let { put("os", it) }
                    entry.app?.let { put("app", it) }
                    entry.linkedAt?.let { put("linked_at", it) }
                    entry.linkedBy?.let { put("linked_by", it) }
                    if (entry.attested) put("attested", true)
                },
            )
        }.toString()
    }
}

@Serializable
private data class DevicesSignedOut(@SerialName("signed_out") val signedOut: Int = 0)

/** `DELETE /v1/me/devices/{deviceId}` (ADR 0291): signs out one other device of the account, after the biometric check. */
suspend fun BipeApiClient.signOutDevice(deviceId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "devices", deviceId), accessToken = accessToken, response = null)
}

/** `POST /v1/me/devices/sign-out-others` (ADR 0291): signs out every device except this one. Returns how many. */
suspend fun BipeApiClient.signOutOtherDevices(accessToken: String): Int =
    send("POST", listOf("v1", "me", "devices", "sign-out-others"), accessToken = accessToken, response = DevicesSignedOut.serializer()).signedOut
