// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import java.util.prefs.Preferences
import chat.bipe.desktop.chat.TypingPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

interface DevicePresenceStorage { fun read(): Boolean?; fun write(enabled: Boolean) }
sealed interface DevicePresencePhase {
    val enabled: Boolean
    data class Saved(override val enabled: Boolean) : DevicePresencePhase
    data class Saving(val previous: Boolean, val requested: Boolean) : DevicePresencePhase { override val enabled = previous && requested }
    data class Failed(override val enabled: Boolean, val requested: Boolean) : DevicePresencePhase
}

/** ADR0050's device-local presence switch also controls sending and showing typing. Off takes effect immediately. */
class DevicePresence(private val scope: CoroutineScope, private val storage: DevicePresenceStorage = WindowsPresenceStorage()) : TypingPreference {
    private val initial = try { DevicePresencePhase.Saved(storage.read() ?: true) }
        catch (_: Exception) { DevicePresencePhase.Failed(false, false) }
    private val mutablePhase = MutableStateFlow<DevicePresencePhase>(initial)
    val phase = mutablePhase.asStateFlow()
    override val enabled get() = phase.value.enabled
    override val changes get() = phase.map { it.enabled }.distinctUntilChanged()
    @Synchronized fun pick(enabled: Boolean) {
        val old = mutablePhase.value
        if (old is DevicePresencePhase.Saving || (old is DevicePresencePhase.Saved && old.enabled == enabled)) return
        mutablePhase.value = DevicePresencePhase.Saving(old.enabled, enabled)
        scope.launch {
            try { storage.write(enabled); mutablePhase.value = DevicePresencePhase.Saved(enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutablePhase.value = DevicePresencePhase.Failed(old.enabled && enabled, enabled) }
        }
    }
}
private class WindowsPresenceStorage : DevicePresenceStorage {
    private val prefs by lazy { Preferences.userRoot().node("chat/bipe/windows") }
    override fun read(): Boolean? = prefs.get("share_presence", null)?.let {
        when (it) { "true" -> true; "false" -> false; else -> error("Invalid presence preference") }
    }
    override fun write(enabled: Boolean) { prefs.putBoolean("share_presence", enabled); prefs.flush() }
}
