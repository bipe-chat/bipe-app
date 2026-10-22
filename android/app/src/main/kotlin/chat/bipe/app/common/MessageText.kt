// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** The chat list preview of an event. undecryptable is the text to show for a message that does not decrypt. */
fun TimelineItemContent.previewText(undecryptable: String): String? {
    val content = (this as? TimelineItemContent.MsgLike)?.content ?: return null
    return when (val kind = content.kind) {
        is MsgLikeKind.Message -> kind.content.body
        is MsgLikeKind.UnableToDecrypt -> undecryptable
        else -> null
    }
}

fun ProfileDetails.displayName(orUserId: String): String =
    (this as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotEmpty() } ?: orUserId
