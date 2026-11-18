// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DeviceAppearanceTest {
    @Test fun storesTheDeviceModeAndRejectsUnknownModes() = runBlocking {
        val storage = Storage().apply { value = "light" }
        val model = DeviceAppearance(listOf("system", "light", "dark"), this, storage)
        assertEquals("light", model.phase.value.mode)
        model.pick("unknown")
        assertEquals("light", model.phase.value.mode)
        model.pick("dark")
        withTimeout(2_000) { model.phase.first { it is DeviceAppearancePhase.Saved } }
        assertEquals("dark", storage.value)
    }

    @Test fun failedStorageRestoresThePreviousModeAndARepeatedChoiceRetries() = runBlocking {
        val storage = Storage().apply { fail = true }
        val model = DeviceAppearance(listOf("system", "light", "dark"), this, storage)
        model.pick("dark")
        withTimeout(2_000) { model.phase.first { it is DeviceAppearancePhase.Failed } }
        assertEquals("system", model.phase.value.mode)
        storage.fail = false
        model.pick("dark")
        withTimeout(2_000) { model.phase.first { it is DeviceAppearancePhase.Saved } }
        assertEquals("dark", model.phase.value.mode)
    }

    private class Storage : DeviceAppearanceStorage {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }
}
