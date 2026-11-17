// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.os.Build
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.appVersionWithBuild
import chat.bipe.app.timeline.AssistantDraftsStore
import chat.bipe.shared.R
import chat.bipe.shared.devices.BipeDevices
import chat.bipe.shared.drafts.BipeAssistantDrafts
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.weblink.BipeMatrixAccountApi
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

/**
 * The device entries of Aparelhos e assistentes (ADR 0291, bipe-shared docs/drafts-room.md "Device entries"): this
 * phone writes its own entry (source self) in the drafts room after the link and again when a field changed, and an
 * approval entry for a device that it approved with Link a computer. AssistantDraftsStore reads the entries.
 */
object DeviceEntries {
    private const val PREFS = "device_entries"
    private val writing = Collections.synchronizedSet(mutableSetOf<String>())
    /** The approval entry outlives the Link a computer screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts writeApproval in a scope that the closing screen does not cancel. */
    fun startApproval(context: Context, session: AccountSession, userAgent: String?, before: Set<String>) {
        val app = context.applicationContext
        scope.launch { writeApproval(app, session, userAgent, before) }
    }

    /**
     * At start and on each return to the front: writes this phone's self entry when it was never written or a field
     * changed (a new app version or system). Creates the drafts room when the account has none.
     */
    suspend fun writeSelf(context: Context, session: AccountSession) {
        val deviceId = runCatching { session.client.deviceId() }.getOrNull() ?: return
        val id = "${session.userId}|$deviceId"
        if (!writing.add(id)) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val linkedKey = "linked_at|$id"
            val sentKey = "sent|$id"
            val linkedAt = if (prefs.contains(linkedKey)) prefs.getLong(linkedKey, 0) else System.currentTimeMillis()
            val entry = BipeDevices.Entry(
                deviceId = deviceId, source = "self", kind = "phone", model = "${Build.MANUFACTURER} ${Build.MODEL}",
                os = "Android ${Build.VERSION.RELEASE}", app = "Bipe ${appVersionWithBuild(context)}",
                linkedAt = linkedAt, linkedBy = null, attested = false,
            )
            // The fields, in a fixed order.
            val fingerprint = listOf(entry.model, entry.os, entry.app, linkedAt.toString()).joinToString("|") { it.orEmpty() }
            if (prefs.getString(sentKey, null) == fingerprint) return
            val room = draftsRoom(context, session) ?: return
            runCatching { room.sendRaw("m.room.message", BipeDevices.content(entry)) }.getOrElse { return }
            prefs.edit().putLong(linkedKey, linkedAt).putString(sentKey, fingerprint).apply()
        } finally {
            writing.remove(id)
        }
    }

    /**
     * Spec linked-devices appear: GET /_matrix/client/v3/devices and the entries of the drafts room, through
     * device_rows. Null when the list did not load.
     */
    suspend fun list(session: AccountSession): BipeDevices.DeviceList? {
        val token = session.accessToken() ?: return null
        val thisId = runCatching { session.client.deviceId() }.getOrNull() ?: return null
        val devices = runCatching { BipeMatrixAccountApi(session.client.homeserver()).devices(token) }.getOrNull() ?: return null
        val drafts = AssistantDraftsStore.state.value
        val entries = if (drafts.userId == session.userId) drafts.deviceEntries else emptyList()
        return BipeDevices.rows(thisId, devices, entries)
    }

    /** The device IDs of the account before an approval, so the new device can be found after it. */
    suspend fun deviceIds(session: AccountSession): Set<String>? {
        val token = session.accessToken() ?: return null
        return runCatching { BipeMatrixAccountApi(session.client.homeserver()).devices(token) }.getOrNull()?.map { it.deviceId }?.toSet()
    }

    /**
     * After Link a computer approved a device: an approval entry from the user agent of the MAS consent page
     * (BipeDevices.userAgentInfo). The new device is the one device that was not in the list before; the list is read
     * a few times while the device signs in. Nothing is written when no rule knows the user agent or when more than one
     * new device shows.
     */
    suspend fun writeApproval(context: Context, session: AccountSession, userAgent: String?, before: Set<String>) {
        val info = userAgent?.let { BipeDevices.userAgentInfo(it) } ?: return
        val ownId = runCatching { session.client.deviceId() }.getOrNull() ?: return
        val matrix = BipeMatrixAccountApi(session.client.homeserver())
        repeat(10) {
            delay(3_000)
            val token = session.accessToken() ?: return@repeat
            val devices = runCatching { matrix.devices(token) }.getOrNull() ?: return@repeat
            val new = devices.filter { it.deviceId !in before && it.deviceId != ownId }
            if (new.size > 1) return
            val device = new.firstOrNull() ?: return@repeat
            val entry = BipeDevices.Entry(
                deviceId = device.deviceId, source = "approval", kind = info.kind, model = info.model, os = info.os,
                app = info.app, linkedAt = System.currentTimeMillis(), linkedBy = ownId, attested = false,
            )
            val room = draftsRoom(context, session) ?: return
            runCatching { room.sendRaw("m.room.message", BipeDevices.content(entry)) }
            return
        }
    }

    /**
     * The drafts room of the account. A writer that finds none creates it (bipe-shared docs/drafts-room.md "The room"):
     * encrypted, invite only, history from the join, muted, then the account data chat.bipe.drafts_room.
     */
    private suspend fun draftsRoom(context: Context, session: AccountSession): Room? {
        // A failed read must never count as "no drafts room yet".
        val json = runCatching { session.client.accountData(BipeRegistries.DRAFTS_ACCOUNT_DATA_TYPE) }.getOrElse { return null }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingKey = "pending_drafts_room|${session.userId}"
        val pending = prefs.getString(pendingKey, null)
        val known = BipeAssistantDrafts.roomId(json)
        if (known != null && known != pending) {
            val room = runCatching { session.client.getRoom(known) }.getOrNull() ?: return null
            return room.takeIf { runCatching { it.membership() }.getOrNull() == Membership.JOINED }
        }
        // Account data that names no valid room: never make a second room over it.
        if (json != null && known == null) return null
        return runCatching {
            val id = pending ?: session.client.createRoom(
                CreateRoomParameters(
                    name = context.getString(R.string.bipe_app_drafts_title), isEncrypted = true, isDirect = false,
                    visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = null,
                    joinRuleOverride = JoinRule.Invite, historyVisibilityOverride = RoomHistoryVisibility.Joined,
                ),
            ).also { created ->
                // If the account data write fails, the next try finishes it instead of creating another room.
                withContext(Dispatchers.IO) { check(prefs.edit().putString(pendingKey, created).commit()) }
            }
            session.client.setAccountData(BipeRegistries.DRAFTS_ACCOUNT_DATA_TYPE, buildJsonObject { put("room_id", id) }.toString())
            prefs.edit().remove(pendingKey).apply()
            val room = session.client.awaitRoomRemoteEcho(id)
            runCatching { session.client.getNotificationSettings().setRoomNotificationMode(id, RoomNotificationMode.MUTE) }
            AssistantDraftsStore.refresh(context, session)
            room
        }.getOrNull()
    }
}
