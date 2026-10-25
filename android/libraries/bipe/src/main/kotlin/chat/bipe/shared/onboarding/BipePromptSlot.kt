// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.onboarding

import android.content.Context
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingPrompt

/**
 * The one prompt slot of an account (registries/onboarding.json promptSlot, bipe-chat/bipe#200): after a slot prompt
 * shows, no other slot prompt shows for [BipeOnboarding.PROMPT_SLOT_HOURS]. The prompt that holds the slot keeps
 * showing. The iOS counterpart is `BipePromptSlot` in BipeKit.
 */
class BipePromptSlot(context: Context, userId: String) {
    private val prefs = context.getSharedPreferences("bipe_prompt_slot", Context.MODE_PRIVATE)
    private val holderKey = "holder_$userId"
    private val sinceKey = "since_$userId"

    /** true when [prompt] may show now. A prompt outside the slot always may. */
    fun allows(prompt: BipeOnboardingPrompt, now: Long = System.currentTimeMillis()): Boolean {
        if (!prompt.usesSlot) return true
        val holder = prefs.getString(holderKey, null) ?: return true
        val since = prefs.getLong(sinceKey, 0L)
        return holder == prompt.name || now - since >= BipeOnboarding.PROMPT_SLOT_HOURS * 3_600_000L
    }

    /** Call when [prompt] shows. It takes the slot unless it already holds it. */
    fun didShow(prompt: BipeOnboardingPrompt, now: Long = System.currentTimeMillis()) {
        if (!prompt.usesSlot || !allows(prompt, now) || prefs.getString(holderKey, null) == prompt.name) return
        prefs.edit().putString(holderKey, prompt.name).putLong(sinceKey, now).apply()
    }
}
