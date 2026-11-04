// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import android.util.Log
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.app.settings.ShowMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.InviteAvatars
import org.matrix.rustcomponents.sdk.MediaPreviewConfig
import org.matrix.rustcomponents.sdk.MediaPreviewConfigListener
import org.matrix.rustcomponents.sdk.MediaPreviews
import org.matrix.rustcomponents.sdk.TaskHandle

/** The setting bipe_chats_show_media (state showMedia of spec timeline-media), as the settings screen stores it. */
fun currentShowMedia(context: Context): ShowMedia {
    val stored = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getString(SettingsKeys.SHOW_MEDIA, null)
    return ShowMedia.entries.firstOrNull { it.id == stored } ?: ShowMedia.ALWAYS
}

/** private: media shows in DMs and in groups that need an invite; in a public group it waits for a tap. */
fun ShowMedia.shows(inPrivateRoom: Boolean): Boolean = when (this) {
    ShowMedia.ALWAYS -> true
    ShowMedia.PRIVATE -> inPrivateRoom
    ShowMedia.NEVER -> false
}

/** MSC4278 media_previews: always = on, private = private, never = off. */
val ShowMedia.policy: MediaPreviews
    get() = when (this) {
        ShowMedia.ALWAYS -> MediaPreviews.ON
        ShowMedia.PRIVATE -> MediaPreviews.PRIVATE
        ShowMedia.NEVER -> MediaPreviews.OFF
    }

fun showMediaOf(policy: MediaPreviews): ShowMedia = when (policy) {
    MediaPreviews.ON -> ShowMedia.ALWAYS
    MediaPreviews.PRIVATE -> ShowMedia.PRIVATE
    MediaPreviews.OFF -> ShowMedia.NEVER
}

/**
 * Spec timeline-media and chat-requests notes (bipe-chat/bipe#827): showMedia and hidePhotos live in the account, in
 * the standard account data m.media_preview_config (MSC4278), so both phones of one person and other Matrix clients
 * agree. The phone keeps a copy for the rows; this follows the current account's value and writes the person's
 * choice. An account without the setting gets the phone's value once.
 */
object MediaPreviewSync {
    private const val TAG = "MediaPreviewSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var client: Client? = null
    private var handle: TaskHandle? = null
    private var job: Job? = null

    /** The current account changed: follow its setting. */
    fun attach(context: Context, session: AccountSession) {
        val appContext = context.applicationContext
        handle?.cancel()
        handle = null
        job?.cancel()
        val client = session.client
        this.client = client
        job = scope.launch {
            val listener = object : MediaPreviewConfigListener {
                override fun onChange(mediaPreviewConfig: MediaPreviewConfig?) {
                    scope.launch { apply(appContext, mediaPreviewConfig, client, writeMissing = false) }
                }
            }
            val subscribed = runCatching { client.subscribeToMediaPreviewConfig(listener) }.getOrNull()
            if (this@MediaPreviewSync.client !== client) {
                subscribed?.cancel()
                return@launch
            }
            handle = subscribed
            try {
                apply(appContext, client.fetchMediaPreviewConfig(), client, writeMissing = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline: keep the phone's copy; the subscription brings the value later.
                Log.i(TAG, "media preview config not fetched now")
            }
        }
    }

    /** The person picked showMedia in settings. */
    fun setShowMedia(context: Context, value: ShowMedia) {
        prefs(context).edit().putString(SettingsKeys.SHOW_MEDIA, value.id).apply()
        val client = client ?: return
        scope.launch {
            runCatching { client.setMediaPreviewDisplayPolicy(value.policy) }
                .onFailure { Log.w(TAG, "media previews not saved in the account") }
        }
    }

    /** The person turned the switch requestPhotos (hidePhotos of spec chat-requests). */
    fun setHideInvitePhotos(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, hide).apply()
        val client = client ?: return
        scope.launch {
            runCatching { client.setInviteAvatarsDisplayPolicy(if (hide) InviteAvatars.OFF else InviteAvatars.ON) }
                .onFailure { Log.w(TAG, "invite avatars not saved in the account") }
        }
    }

    private suspend fun apply(context: Context, config: MediaPreviewConfig?, client: Client, writeMissing: Boolean) {
        if (this.client !== client) return
        val prefs = prefs(context)
        val previews = config?.mediaPreviews
        if (previews != null) {
            prefs.edit().putString(SettingsKeys.SHOW_MEDIA, showMediaOf(previews).id).apply()
        } else if (writeMissing) {
            runCatching { client.setMediaPreviewDisplayPolicy(currentShowMedia(context).policy) }
        }
        val avatars = config?.inviteAvatars
        if (avatars != null) {
            prefs.edit().putBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, avatars == InviteAvatars.OFF).apply()
        } else if (writeMissing) {
            val hide = prefs.getBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, true)
            runCatching { client.setInviteAvatarsDisplayPolicy(if (hide) InviteAvatars.OFF else InviteAvatars.ON) }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
}

/**
 * Spec timeline-media fromStranger (bipe-chat/bipe#827): the DMs the person accepted from chat-requests on this phone.
 * Room IDs only, on the phone.
 */
object RequestRooms {
    private const val KEY = "request_rooms"
    /** The newest this many rooms are kept. */
    private const val LIMIT = 500

    fun remember(context: Context, roomId: String) {
        val prefs = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
        val rooms = (prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()) - roomId
        prefs.edit().putString(KEY, (rooms + roomId).takeLast(LIMIT).joinToString("\n")).apply()
    }

    fun contains(context: Context, roomId: String): Boolean =
        context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)?.split('\n')?.contains(roomId) == true
}
