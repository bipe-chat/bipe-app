// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import chat.bipe.shared.generated.BipeSettingsRow

/**
 * The setting bipe_chats_show_media (state showMedia of spec timeline-media). Stored in the
 * preferences file bipe_settings under the settings row id chats.showMedia, so the settings
 * screen writes the same value.
 */
enum class ShowMedia(val value: String) {
    ALWAYS("always"),
    PRIVATE_ONLY("privateOnly"),
    NEVER("never"),
    ;

    /** privateOnly: media shows in DMs and in groups that need an invite; in a public group it waits for a tap. */
    fun shows(inPrivateRoom: Boolean): Boolean = when (this) {
        ALWAYS -> true
        PRIVATE_ONLY -> inPrivateRoom
        NEVER -> false
    }

    companion object {
        const val PREFERENCES = "bipe_settings"

        fun current(context: Context): ShowMedia {
            val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(BipeSettingsRow.CHATS_SHOW_MEDIA.id, null)
            return entries.firstOrNull { it.value == stored } ?: ALWAYS
        }
    }
}
