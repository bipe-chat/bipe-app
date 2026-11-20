// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.shared.R

internal fun stateLineString(key: String): Int = when (key) {
    "bipe_app_state_banned" -> R.string.bipe_app_state_banned
    "bipe_app_state_description_changed" -> R.string.bipe_app_state_description_changed
    "bipe_app_state_description_removed" -> R.string.bipe_app_state_description_removed
    "bipe_app_state_invited" -> R.string.bipe_app_state_invited
    "bipe_app_state_joined_many" -> R.string.bipe_app_state_joined_many
    "bipe_app_state_joined_one" -> R.string.bipe_app_state_joined_one
    "bipe_app_state_left_many" -> R.string.bipe_app_state_left_many
    "bipe_app_state_left_one" -> R.string.bipe_app_state_left_one
    "bipe_app_state_made_admin" -> R.string.bipe_app_state_made_admin
    "bipe_app_state_made_moderator" -> R.string.bipe_app_state_made_moderator
    "bipe_app_state_name_removed" -> R.string.bipe_app_state_name_removed
    "bipe_app_state_names_more" -> R.string.bipe_app_state_names_more
    "bipe_app_state_names_two" -> R.string.bipe_app_state_names_two
    "bipe_app_state_photo_changed" -> R.string.bipe_app_state_photo_changed
    "bipe_app_state_photo_removed" -> R.string.bipe_app_state_photo_removed
    "bipe_app_state_removed" -> R.string.bipe_app_state_removed
    "bipe_app_state_removed_admin" -> R.string.bipe_app_state_removed_admin
    "bipe_app_state_removed_moderator" -> R.string.bipe_app_state_removed_moderator
    "bipe_app_state_renamed" -> R.string.bipe_app_state_renamed
    else -> error("Unknown group state string")
}
