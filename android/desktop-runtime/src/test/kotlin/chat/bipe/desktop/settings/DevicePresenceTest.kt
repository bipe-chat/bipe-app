// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DevicePresenceTest {
    @Test fun enablingWaitsForPersistenceAndARepeatedClickCannotDispatchAnotherWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var writes = 0
        try {
            val presence = DevicePresence(scope, object : DevicePresenceStorage {
                override fun read() = false
                override fun write(enabled: Boolean) { writes++; entered.complete(Unit); runBlocking { release.await() } }
            })
            presence.pick(true); entered.await(); presence.pick(true)
            assertFalse(presence.enabled); assertTrue(presence.phase.value is DevicePresencePhase.Saving)
            release.complete(Unit)
            withTimeout(2_000) { presence.phase.first { it is DevicePresencePhase.Saved && it.enabled } }
            assertEquals(1, writes)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun unreadablePreferenceAndAFailedDisableRemainOffUntilAnExplicitSuccessfulRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val corrupt = DevicePresence(scope, object : DevicePresenceStorage {
                override fun read(): Boolean? = error("Corrupt")
                override fun write(enabled: Boolean) { }
            })
            assertFalse(corrupt.enabled)
            var fail = true
            val presence = DevicePresence(scope, object : DevicePresenceStorage {
                override fun read() = true
                override fun write(enabled: Boolean) { if (fail) error("Synthetic storage failure") }
            })
            presence.pick(false); assertFalse(presence.enabled)
            withTimeout(2_000) { presence.phase.first { it is DevicePresencePhase.Failed } }
            assertFalse(presence.enabled)
            fail = false; presence.pick(false)
            withTimeout(2_000) { presence.phase.first { it is DevicePresencePhase.Saved } }
            assertFalse(presence.enabled)
        } finally { scope.cancel() }
    }
}
