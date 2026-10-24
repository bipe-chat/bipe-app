// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries

/**
 * Spec scam-warning, money pause (security.md rule 9, trust-ui.md): copy and links of a message with a money warning
 * stay off for [BipeRegistries.SCAM_MONEY_PAUSE_SECONDS]. One pause per message for the life of this value (one open chat).
 * Same rules as BipeMoneyPause on iOS.
 */
data class BipeMoneyPause(private val endsAtMs: Map<String, Long> = emptyMap()) {
    /** The pause after [id]'s card first shows, or this value unchanged when it does not apply or already ran. */
    fun started(id: String, kind: ScamWarningKind, nowMs: Long = System.currentTimeMillis()): BipeMoneyPause {
        if (!applies(kind) || id in endsAtMs) return this
        return BipeMoneyPause(endsAtMs + (id to nowMs + BipeRegistries.SCAM_MONEY_PAUSE_SECONDS * 1_000L))
    }

    /** Whole seconds left, rounded up; 0 when the pause never started or has ended. */
    fun secondsLeft(id: String, nowMs: Long = System.currentTimeMillis()): Int {
        val left = (endsAtMs[id] ?: return 0) - nowMs
        return if (left <= 0) 0 else ((left + 999) / 1_000).toInt()
    }

    companion object {
        /** `true` for the kinds in [BipeRegistries.scamMoneyPauseKinds]. */
        fun applies(kind: ScamWarningKind): Boolean = kind.id in BipeRegistries.scamMoneyPauseKinds
    }
}
