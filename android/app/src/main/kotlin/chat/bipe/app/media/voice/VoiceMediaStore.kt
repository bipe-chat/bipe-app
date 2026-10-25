// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.voice

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeKeptMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State media expired of spec voice-message (ADR 0164): media URLs whose download answered 404 in this session.
 * Port of `VoiceMediaExpiry` on iOS. Media URLs are unique, so one set serves every account and chat.
 */
object VoiceMediaExpiry {
    private val _expired = MutableStateFlow<Set<String>>(emptySet())
    val expired: StateFlow<Set<String>> = _expired.asStateFlow()

    fun markExpired(url: String) {
        _expired.value = _expired.value + url
    }

    fun isExpired(url: String): Boolean = url in _expired.value
}

/**
 * Spec voice-message, actions register, keep, unkeep and keepScam (ADR 0164, bipe-chat/bipe#622). One store per
 * account. Port of `VoiceMediaStore` on iOS. The server deletes a registered voice upload after its time unless an
 * account keeps it.
 */
class VoiceMediaStore private constructor(context: Context, private val session: AccountSession) {
    enum class KeepResult { DONE, LIMIT, FAILED }

    private val prefs = context.applicationContext.getSharedPreferences("bipe_voice_media", Context.MODE_PRIVATE)
    private val registeredKey = "registered.${session.userId}"
    private val _kept = MutableStateFlow<Map<String, BipeKeptMedia.Reason>>(emptyMap())

    /** State kept: mxc URI to reason. */
    val kept: StateFlow<Map<String, BipeKeptMedia.Reason>> = _kept.asStateFlow()

    private val lock = Mutex()
    private var loaded = false
    private val registering = mutableSetOf<String>()
    private val scamTried = mutableSetOf<String>()

    /** GET /v1/me/kept-media once per session; again after a failure. */
    suspend fun load() {
        lock.withLock {
            if (loaded) return
            val list = call { api.keptMedia(it) } ?: return
            _kept.value = list.associate { it.mediaId to it.reason }
            loaded = true
        }
    }

    /** Action register: an own voice message whose event is sent. Once per media ID on this phone. */
    suspend fun register(url: String, transcript: Boolean) {
        val (server, mediaId) = parts(url) ?: return
        synchronized(registering) {
            if (url in registering || url in registered()) return
            registering += url
        }
        try {
            BipeApiClient.withTokenRetry({ session.accessToken() }) { api.registerVoiceMedia(server, mediaId, transcript, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: BipeApiException) {
            // Not this account's upload on this server: it can never register. Do not try again.
            if ((error as? BipeApiException.Server)?.code != BipeApiErrorCode.NOT_FOUND) return
        } finally {
            synchronized(registering) { registering -= url }
        }
        synchronized(registering) {
            val list = (registered() + url).takeLast(REGISTERED_LIMIT)
            prefs.edit().putString(registeredKey, list.joinToString("\n")).apply()
        }
    }

    /** Actions keep and keepScam. */
    suspend fun keep(url: String, reason: BipeKeptMedia.Reason): KeepResult {
        val (server, mediaId) = parts(url) ?: return KeepResult.FAILED
        return try {
            BipeApiClient.withTokenRetry({ session.accessToken() }) { api.keepMedia(server, mediaId, reason, it) }
            _kept.value = _kept.value + (url to reason)
            KeepResult.DONE
        } catch (error: CancellationException) {
            throw error
        } catch (error: BipeApiException) {
            if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.KEEP_LIMIT) KeepResult.LIMIT else KeepResult.FAILED
        }
    }

    /** Action keepScam: once per media ID in the session, and only when the audio is not kept yet. */
    suspend fun keepForScam(url: String) {
        load()
        if (!loaded || url in _kept.value) return
        synchronized(scamTried) { if (!scamTried.add(url)) return }
        keep(url, BipeKeptMedia.Reason.SCAM)
    }

    /** Action unkeep. */
    suspend fun unkeep(url: String): Boolean {
        val (server, mediaId) = parts(url) ?: return false
        return call { api.unkeepMedia(server, mediaId, it) }?.let {
            _kept.value = _kept.value - url
            true
        } ?: false
    }

    private fun registered(): List<String> = prefs.getString(registeredKey, null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    private suspend fun <T> call(request: suspend (String) -> T): T? = try {
        BipeApiClient.withTokenRetry({ session.accessToken() }, request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: BipeApiException) {
        null
    }

    companion object {
        private val api = BipeApiClient(AppConfig.API_BASE_URL)
        private val stores = mutableMapOf<String, VoiceMediaStore>()

        /** The newest registered media IDs kept on the phone. Older ones are not sent again either: their rows are long sent. */
        private const val REGISTERED_LIMIT = 2_000

        @Synchronized
        fun store(context: Context, session: AccountSession): VoiceMediaStore =
            stores.getOrPut(session.userId) { VoiceMediaStore(context, session) }

        /** The server name and media ID of an `mxc://<server>/<mediaId>` URI. */
        fun parts(url: String): Pair<String, String>? {
            if (!url.startsWith("mxc://")) return null
            val rest = url.removePrefix("mxc://").split("/")
            if (rest.size != 2 || rest[0].isEmpty() || rest[1].isEmpty()) return null
            return rest[0] to rest[1]
        }
    }
}
