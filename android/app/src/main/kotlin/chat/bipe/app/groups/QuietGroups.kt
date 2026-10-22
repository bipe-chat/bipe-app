// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.content.Context
import chat.bipe.app.account.AccountSession
import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.Action
import org.matrix.rustcomponents.sdk.PushCondition
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RuleKind
import org.matrix.rustcomponents.sdk.Tweak

/**
 * Quiet groups by default (ADR 0026, specs group-create and chat-notifications notes). Once per account, after the
 * first sync: the account default for groups becomes mentions and keywords, and the override rule
 * chat.bipe.poll_start makes a new poll notify in a quiet group.
 */
object QuietGroups {
    const val POLL_RULE_ID = "chat.bipe.poll_start"

    private const val PREFS = "bipe_quiet_groups"

    suspend fun applyOnce(context: Context, session: AccountSession) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(session.userId, false)) return
        val settings = session.client.getNotificationSettings()
        // The push rules arrive with the first sync.
        var rules: String? = null
        for (attempt in 1..10) {
            rules = runCatching { settings.getRawPushRules() }.getOrNull()
            if (rules != null) break
            delay(3_000)
        }
        val raw = rules ?: return
        runCatching {
            if (!raw.contains(POLL_RULE_ID)) {
                settings.setCustomPushRule(
                    POLL_RULE_ID,
                    RuleKind.Override,
                    listOf(Action.Notify, Action.SetTweak(Tweak.Sound("default"))),
                    listOf(PushCondition.EventMatch("type", "*.poll.start")),
                )
            }
            settings.setDefaultRoomNotificationMode(false, false, RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
            // Mentions-only in an encrypted group needs the server to push encrypted events (#118). Until it does,
            // the encrypted default stays as it is and the next start tries again.
            if (!settings.canHomeserverPushEncryptedEventToDevice()) return
            settings.setDefaultRoomNotificationMode(true, false, RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
            prefs.edit().putBoolean(session.userId, true).apply()
        }
    }
}
