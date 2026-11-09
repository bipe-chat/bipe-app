// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.Context
import android.util.Log
import chat.bipe.app.chatlist.MuralSetting
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.shared.BipeSyncedSettings
import chat.bipe.shared.generated.BipeChatBackground
import chat.bipe.shared.generated.BipePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.matrix.rustcomponents.sdk.Client

/**
 * Spec registries/synced-settings.json (bipe-chat/bipe#1161): keeps this device's copy and the account's settings
 * together. The copy of the current account applies to the whole app. A failure changes nothing on the server, and
 * the next attach or foreground tries again.
 */
object SyncedSettings {
    private const val TAG = "SyncedSettings"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var client: Client? = null
    @Volatile private var userId = ""

    /** The current account changed. */
    fun attach(context: Context, client: Client, userId: String) {
        this.client = client
        this.userId = userId
        refresh(context.applicationContext)
    }

    /** The person changed a setting on this device. */
    fun changed(context: Context, key: String) {
        if (client == null) return
        val prefs = prefs(context)
        val pending = prefs.getStringSet(pendingKey(userId), emptySet()).orEmpty() + key
        prefs.edit().putStringSet(pendingKey(userId), pending).apply()
        refresh(context.applicationContext)
    }

    /** Read the account's settings again: when the app comes to the front (ADR 0273), and after a change here. */
    fun refresh(context: Context) {
        val client = client ?: return
        val userId = userId
        scope.launch {
            runCatching { sync(context, client, userId) }.onFailure { Log.i(TAG, "settings not synced now") }
        }
    }

    private suspend fun sync(context: Context, client: Client, userId: String) {
        val prefs = prefs(context)
        val pending = prefs.getStringSet(pendingKey(userId), emptySet()).orEmpty().toSet()
        val first = !prefs.getBoolean(firstKey(userId), false)
        val server = BipeSyncedSettings.parse(serverAccountData(client, BipeSyncedSettings.EVENT_TYPE))
        val result = BipeSyncedSettings.reconcile(server, localValues(context, userId), pending, first)
        if (result != server) client.setAccountData(BipeSyncedSettings.EVENT_TYPE, BipeSyncedSettings.content(result))
        val remaining = prefs.getStringSet(pendingKey(userId), emptySet()).orEmpty() - pending
        prefs.edit().putBoolean(firstKey(userId), true).putStringSet(pendingKey(userId), remaining).apply()
        if (this.client === client) apply(context, result, userId)
    }

    private fun localValues(context: Context, userId: String): Map<String, JsonElement> = buildMap {
        val prefs = prefs(context)
        if (prefs.contains(SettingsKeys.READ_RECEIPTS)) {
            put(BipeSyncedSettings.READ_RECEIPTS, JsonPrimitive(prefs.getBoolean(SettingsKeys.READ_RECEIPTS, true)))
        }
        prefs.getString(AppTheme.PALETTE_KEY, null)?.let { put(BipeSyncedSettings.PALETTE, JsonPrimitive(it)) }
        prefs.getString(AppTheme.CHAT_BACKGROUND_KEY, null)?.let { put(BipeSyncedSettings.CHAT_BACKGROUND, JsonPrimitive(it)) }
        put(BipeSyncedSettings.MURAL, JsonPrimitive(userId in MuralSetting.of(context).accounts.value))
    }

    /** An unknown id or a wrong type changes nothing. */
    private fun apply(context: Context, settings: Map<String, JsonElement>, userId: String) {
        BipeSyncedSettings.bool(settings, BipeSyncedSettings.READ_RECEIPTS)?.let {
            prefs(context).edit().putBoolean(SettingsKeys.READ_RECEIPTS, it).apply()
        }
        BipeSyncedSettings.bool(settings, BipeSyncedSettings.MURAL)?.let { on ->
            val mural = MuralSetting.of(context)
            if ((userId in mural.accounts.value) != on) mural.set(userId, on)
        }
        BipeSyncedSettings.string(settings, BipeSyncedSettings.PALETTE)?.let { id ->
            val palette = BipePalette.entries.firstOrNull { it.id == id }
            if (palette != null && palette != AppTheme.palette) AppTheme.save(context, palette)
        }
        BipeSyncedSettings.string(settings, BipeSyncedSettings.CHAT_BACKGROUND)?.let { id ->
            val background = BipeChatBackground.entries.firstOrNull { it.id == id }
            if (background != null && background != AppTheme.chatBackground) AppTheme.saveChatBackground(context, background)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
    private fun firstKey(userId: String) = "settings_synced_$userId"
    private fun pendingKey(userId: String) = "settings_sync_pending_$userId"
}
