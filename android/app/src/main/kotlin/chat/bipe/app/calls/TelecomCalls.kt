// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The call registered with the system through core-telecom (bipe-chat/bipe#760): Bluetooth, headset and car buttons,
 * audio focus, and the call in the system's call state. One call at a time. A failure leaves the call without the
 * system: the app's own screens still work.
 */
object TelecomCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: CallsManager? = null
    private var control: CallControlScope? = null
    private var job: Job? = null
    private var video = false

    private fun manager(context: Context): CallsManager? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED) return null
        manager?.let { return it }
        return runCatching {
            CallsManager(context.applicationContext).also {
                it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            }
        }.getOrNull()?.also { manager = it }
    }

    /** A ringing call. [onAnswer] and [onDisconnect] come from the system (a headset button, the car). */
    fun incoming(context: Context, name: String, video: Boolean, onAnswer: () -> Unit, onDisconnect: () -> Unit) =
        add(context, name, video, CallAttributesCompat.DIRECTION_INCOMING, onAnswer, onDisconnect)

    /** A call this phone starts (spec call start). */
    fun outgoing(context: Context, name: String, video: Boolean, onDisconnect: () -> Unit) =
        add(context, name, video, CallAttributesCompat.DIRECTION_OUTGOING, {}, onDisconnect)

    @android.annotation.SuppressLint("MissingPermission")
    private fun add(context: Context, name: String, video: Boolean, direction: Int, onAnswer: () -> Unit, onDisconnect: () -> Unit) {
        end()
        val manager = manager(context) ?: return
        this.video = video
        val attributes = CallAttributesCompat(
            displayName = name,
            address = Uri.fromParts("bipe", name, null),
            direction = direction,
            callType = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = 0,
        )
        job = scope.launch {
            runCatching {
                manager.addCall(
                    attributes,
                    onAnswer = { onAnswer() },
                    onDisconnect = {
                        control = null
                        onDisconnect()
                    },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    control = this
                }
            }
        }
    }

    /** The app answered a ringing call. */
    fun answered() {
        val control = control ?: return
        val type = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        scope.launch { runCatching { control.answer(type) } }
    }

    /** The call is connected (an outgoing call). */
    fun active() {
        val control = control ?: return
        scope.launch { runCatching { control.setActive() } }
    }

    /** The app ended, declined or missed the call. */
    fun end() {
        val control = control
        this.control = null
        val job = job
        this.job = null
        scope.launch {
            runCatching { control?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
            job?.cancel()
        }
    }
}
