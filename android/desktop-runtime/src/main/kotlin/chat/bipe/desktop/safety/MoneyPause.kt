// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.safety

import chat.bipe.core.ScamKind
import chat.bipe.shared.generated.BipeRegistries

/** One room-open clock, driven by monotonic elapsed milliseconds. Policy comes from the shared registry. */
class MoneyPause {
    private val ends = mutableMapOf<String, Long>()

    @Synchronized fun shown(id: String, kind: ScamKind, nowMs: Long) {
        if (kind.registryId() !in BipeRegistries.scamMoneyPauseKinds || id in ends) return
        ends[id] = nowMs + BipeRegistries.SCAM_MONEY_PAUSE_SECONDS * 1_000L
    }

    @Synchronized fun remaining(nowMs: Long): Map<String, Int> = ends.mapValues { (_, end) ->
        val left = (end - nowMs).coerceAtLeast(0)
        ((left + 999) / 1_000).toInt()
    }

    companion object { fun nowMs(): Long = System.nanoTime() / 1_000_000L }
}
