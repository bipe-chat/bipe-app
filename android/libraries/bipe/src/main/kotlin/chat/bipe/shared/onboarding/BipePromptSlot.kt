// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.onboarding

import android.content.Context
import chat.bipe.shared.generated.BipeChatListPrompt
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

    /** Spec chat-list-prompt (bipe-chat/bipe#894): the one card of the chat list, from the entries whose condition holds. */
    fun chatListCard(eligible: Set<BipeChatListPrompt>, now: Long = System.currentTimeMillis()): BipeChatListPrompt? =
        chatListCard(eligible, prefs.getString(holderKey, null)?.let(::keyOf), prefs.getLong(sinceKey, 0L), now)

    /** Call when [card] shows. A preempting card never takes the slot. The holder is kept as the enum name, like [didShow]. */
    fun didShow(card: BipeChatListPrompt, now: Long = System.currentTimeMillis()) {
        if (card.preempts || prefs.getString(holderKey, null) == card.name || chatListCard(setOf(card), now) != card) return
        prefs.edit().putString(holderKey, card.name).putLong(sinceKey, now).apply()
    }

    companion object {
        /**
         * The pick rule. Reference: chatListPrompt() in bipe-shared tools/lib/registries.mjs; vectors in
         * onboarding-schedule.json chatListPrompt. [holder] is the registry key. A preempting entry wins at once; else
         * the first entry in queue order that the slot allows (no holder, the holder itself, or PROMPT_SLOT_HOURS since
         * the holder took the slot).
         */
        fun chatListCard(eligible: Set<BipeChatListPrompt>, holder: String?, sinceMs: Long, nowMs: Long): BipeChatListPrompt? {
            val entries = BipeChatListPrompt.entries.filter { it in eligible }
            entries.firstOrNull { it.preempts }?.let { return it }
            return entries.firstOrNull { entry ->
                holder == null || holder == entry.key || nowMs - sinceMs >= BipeOnboarding.PROMPT_SLOT_HOURS * 3_600_000L
            }
        }

        /** The registry key of a stored enum name: PIN_REMINDER is pinReminder. */
        private fun keyOf(name: String): String {
            val parts = name.lowercase().split('_')
            return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }
    }
}
