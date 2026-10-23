// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.app.settings.ShowMedia

/** The setting bipe_chats_show_media (state showMedia of spec timeline-media), as the settings screen stores it. */
fun currentShowMedia(context: Context): ShowMedia {
    val stored = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getString(SettingsKeys.SHOW_MEDIA, null)
    return ShowMedia.entries.firstOrNull { it.id == stored } ?: ShowMedia.ALWAYS
}

/** private: media shows in DMs and in groups that need an invite; in a public group it waits for a tap. */
fun ShowMedia.shows(inPrivateRoom: Boolean): Boolean = when (this) {
    ShowMedia.ALWAYS -> true
    ShowMedia.PRIVATE -> inPrivateRoom
    ShowMedia.NEVER -> false
}
