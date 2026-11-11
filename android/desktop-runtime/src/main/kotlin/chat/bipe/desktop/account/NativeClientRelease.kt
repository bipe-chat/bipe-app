// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import kotlinx.coroutines.withTimeout

/** Sync/listeners and accepted draft writes close first. Pause drains SDK queues and SQLite pools before drop. */
internal class NativeClientRelease(
    private val pause: suspend () -> Unit,
    private val release: () -> Unit,
    private val drain: suspend () -> Unit,
) : AccountResource {
    private enum class Phase { OPEN, PAUSED, DRAINING, CLOSED }
    private var phase = Phase.OPEN

    override suspend fun close() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.OPEN) {
            withTimeout(15_000) { pause() }
            phase = Phase.PAUSED
        }
        if (phase == Phase.PAUSED) {
            release()
            phase = Phase.DRAINING
        }
        drain()
        phase = Phase.CLOSED
    }
}
