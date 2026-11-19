// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import chat.bipe.shared.generated.BipeGroupPowerLevels

/** The three roles of a group (spec group-members, bipe-chat/bipe#733), in the order of the members list. */
enum class GroupRole {
    ADMIN, MODERATOR, MEMBER;

    companion object {
        /** A level between two roles is the lower one. */
        fun of(level: Long): GroupRole = when {
            level >= BipeGroupPowerLevels.ADMIN -> ADMIN
            level >= BipeGroupPowerLevels.MODERATOR -> MODERATOR
            else -> MEMBER
        }
    }
}
