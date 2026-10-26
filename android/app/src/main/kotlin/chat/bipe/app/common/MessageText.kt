// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import chat.bipe.app.media.MediaContent
import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.TimelineItemContent

/**
 * The chat list preview of an event. undecryptable is the text to show for a message that does not decrypt;
 * label resolves the media kind labels (spec timeline-media notes, voice-message notes).
 */
fun TimelineItemContent.previewText(undecryptable: String, label: (Int) -> String): String? {
    // Spec incoming-call: a call shows as the call kind; ChatListModel shows a missed one as missed.
    if (this is TimelineItemContent.RtcNotification) {
        if (activeMembers.isNotEmpty()) return label(R.string.bipe_call_ongoing)
        return label(if (callIntent == "video") R.string.bipe_call_video else R.string.bipe_call_voice)
    }
    val content = (this as? TimelineItemContent.MsgLike)?.content ?: return null
    return when (val kind = content.kind) {
        is MsgLikeKind.Message -> when (val media = MediaContent.of(kind.content.msgType)) {
            is MediaContent.Media -> label(media.media.kindLabel)
            is MediaContent.Voice -> if (media.voice.isVoice) label(R.string.bipe_app_media_voice) else media.voice.filename
            null -> kind.content.body
        }
        is MsgLikeKind.Sticker -> label(R.string.bipe_app_media_sticker)
        is MsgLikeKind.UnableToDecrypt -> undecryptable
        else -> null
    }
}

fun ProfileDetails.displayName(orUserId: String): String =
    (this as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotEmpty() } ?: orUserId
