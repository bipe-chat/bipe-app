// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface DeviceAppearanceStorage {
    fun read(): String?
    fun write(value: String)
}
sealed interface DeviceAppearancePhase {
    val mode: String
    data class Saved(override val mode: String) : DeviceAppearancePhase
    data class Saving(val previous: String, override val mode: String) : DeviceAppearancePhase
    data class Failed(override val mode: String, val requested: String) : DeviceAppearancePhase
}

/** Theme mode belongs to this Windows user/device, not to an account or a global server event. */
class DeviceAppearance(private val choices: List<String>, private val scope: CoroutineScope,
                       private val storage: DeviceAppearanceStorage = WindowsAppearanceStorage()) {
    private val initial = runCatching { storage.read() }.getOrNull()?.takeIf { it in choices } ?: choices.first()
    private val mutablePhase = MutableStateFlow<DeviceAppearancePhase>(DeviceAppearancePhase.Saved(initial))
    val phase = mutablePhase.asStateFlow()

    @Synchronized fun pick(mode: String) {
        val old = mutablePhase.value
        if (mode !in choices || old is DeviceAppearancePhase.Saving || (old is DeviceAppearancePhase.Saved && old.mode == mode)) return
        mutablePhase.value = DeviceAppearancePhase.Saving(old.mode, mode)
        scope.launch {
            try { storage.write(mode); mutablePhase.value = DeviceAppearancePhase.Saved(mode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                runCatching { storage.write(old.mode) }
                mutablePhase.value = DeviceAppearancePhase.Failed(old.mode, mode)
            }
        }
    }
}

private class WindowsAppearanceStorage : DeviceAppearanceStorage {
    private val prefs by lazy { Preferences.userRoot().node("chat/bipe/windows") }
    override fun read(): String? = prefs.get("appearance", null)
    override fun write(value: String) { prefs.put("appearance", value); prefs.flush() }
}
