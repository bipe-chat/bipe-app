// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.onboarding

import android.content.Context
import chat.bipe.shared.generated.BipeOnboarding

/**
 * The bring-people reminder rule (registries/onboarding.json bringPeople, bipe-chat/bipe#752). Port of
 * `bringPeopleReminder()` in bipe-shared tools/lib/registries.mjs; must match test-vectors/onboarding-schedule.json.
 * The iOS counterpart is `BipeBringPeople` in BipeKit.
 */
object BipeBringPeople {
    enum class Kind(val id: String) { PENDING("pending"), EXPIRED("expired") }

    /** One filled slot as the rule sees it. [status] is the invite status (pending, used, expired, revoked). */
    data class RuleSlot(val status: String, val sentAt: Long)

    data class Pick(val slot: Int, val kind: Kind)

    private const val DAY = 86_400_000L

    /** The slot that the weekly reminder names, or null. The oldest qualifying slot wins; on a tie, the first one. */
    fun reminder(declines: Int, lastAskAt: Long?, slots: List<RuleSlot>, now: Long): Pick? {
        if (declines >= BipeOnboarding.BRING_PEOPLE_STOP_AFTER_DECLINES) return null
        if (lastAskAt == null || now - lastAskAt < BipeOnboarding.BRING_PEOPLE_FOLLOW_UP_EVERY_DAYS * DAY) return null
        var best: Pick? = null
        slots.forEachIndexed { index, slot ->
            val kind = when {
                slot.status == "expired" -> Kind.EXPIRED
                slot.status == "pending" && now - slot.sentAt >= BipeOnboarding.BRING_PEOPLE_PENDING_AFTER_DAYS * DAY -> Kind.PENDING
                else -> null
            } ?: return@forEachIndexed
            val current = best
            if (current == null || slot.sentAt < slots[current.slot].sentAt) best = Pick(index, kind)
        }
        return best
    }
}

/**
 * What bring-people remembers per account on this phone. Invite IDs only: the names stay on the server (Invite.name)
 * and the links stay in `BipeInviteMemory`.
 */
class BipeBringPeopleMemory(context: Context, userId: String) {
    private val prefs = context.getSharedPreferences("bipe_bring_people", Context.MODE_PRIVATE)
    private val signedUpKey = "signed_up_$userId"
    private val sheetShownKey = "sheet_shown_$userId"
    private val declinesKey = "declines_$userId"
    private val lastAskKey = "last_ask_$userId"
    private val invitesKey = "invites_$userId"

    /** Set by signup-create: this account signed up on this phone, so the first sheet may show. */
    fun markSignedUp() = prefs.edit().putBoolean(signedUpKey, true).apply()

    /** Trigger firstChatListAfterSignup: signed up here, the sheet never showed, and asks did not stop. */
    val sheetDue: Boolean get() = prefs.getBoolean(signedUpKey, false) && !prefs.getBoolean(sheetShownKey, false) && !stopped

    val declines: Int get() = prefs.getInt(declinesKey, 0)

    /** After BRING_PEOPLE_STOP_AFTER_DECLINES declines, no bring-people ask shows again on this account. */
    val stopped: Boolean get() = declines >= BipeOnboarding.BRING_PEOPLE_STOP_AFTER_DECLINES

    val lastAskAt: Long? get() = prefs.getLong(lastAskKey, 0L).takeIf { it > 0L }

    /** The invite ID of each filled slot, in the order they were filled. */
    val inviteIds: List<String>
        get() = prefs.getString(invitesKey, null)?.split(",")?.filter { it.isNotEmpty() }.orEmpty()

    /** The sheet showed (first time or from the first-list card), or the person answered a reminder. */
    fun asked(now: Long = System.currentTimeMillis(), sheet: Boolean = false) {
        val edit = prefs.edit().putLong(lastAskKey, now)
        if (sheet) edit.putBoolean(sheetShownKey, true)
        edit.apply()
    }

    /** Agora não on the sheet or on the reminder. */
    fun decline(now: Long = System.currentTimeMillis()) {
        prefs.edit().putInt(declinesKey, declines + 1).putLong(lastAskKey, now).apply()
    }

    /** Puts [inviteId] in slot [index]; an index past the end adds a slot. */
    fun setInvite(inviteId: String, index: Int) {
        val ids = inviteIds.toMutableList()
        if (index < ids.size) ids[index] = inviteId else ids.add(inviteId)
        prefs.edit().putString(invitesKey, ids.joinToString(",")).apply()
    }

    /** Keeps only the slots whose invite the server still lists as pending, used or expired. */
    fun keep(ids: Set<String>) {
        prefs.edit().putString(invitesKey, inviteIds.filter(ids::contains).joinToString(",")).apply()
    }
}
