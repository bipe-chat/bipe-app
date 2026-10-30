// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import chat.bipe.app.media.MediaContent
import chat.bipe.shared.BipeHandleResolver
import chat.bipe.shared.BipeServers
import chat.bipe.shared.NameNormalizer
import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.MessageType
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
            // Spec location-view notes: the chat list says "Location".
            null -> if (kind.content.msgType is MessageType.Location) label(R.string.bipe_app_location_title) else kind.content.body
        }
        is MsgLikeKind.Sticker -> label(R.string.bipe_app_media_sticker)
        is MsgLikeKind.LiveLocation -> label(R.string.bipe_app_location_live_title)
        is MsgLikeKind.UnableToDecrypt -> undecryptable
        else -> null
    }
}

fun ProfileDetails.displayName(orUserId: String): String =
    (this as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotEmpty() } ?: orUserId

/** The SDK's flag: another member of the room has the same display name. */
val ProfileDetails.isNameAmbiguous: Boolean
    get() = (this as? ProfileDetails.Ready)?.displayNameAmbiguous == true

/** The session's handle resolver for screens that have no [chat.bipe.app.people.PeopleService]. null: no @ shows. */
val LocalBipeHandles = staticCompositionLocalOf<BipeHandleResolver?> { null }

/** Spec timeline-rows and group-members nameAmbiguous (bipe-chat/bipe#825): members whose names look the same. */
object MemberNames {
    /**
     * The user IDs whose name, after [NameNormalizer] (security rule 8), another member also has.
     * A look-alike "Mãe" with a Cyrillic "а" counts as the same name.
     */
    fun ambiguous(names: Map<String, String>): Set<String> =
        names.entries
            .groupBy { NameNormalizer.normalize(it.value) }
            .filterKeys { it.isNotEmpty() }
            .values
            .filter { it.size > 1 }
            .flatMapTo(mutableSetOf()) { group -> group.map { it.key } }

    /**
     * The name with what tells the member apart: "Maria @maria1" for a Bipe user once the @ is known,
     * the Matrix ID for a user of another server, the name alone while the @ loads.
     */
    fun label(name: String, userId: String, handle: String?): String = when {
        BipeServers.isBipeUserId(userId) -> handle?.let { "$name @$it" } ?: name
        else -> "$name $userId"
    }
}

/** [name] with the @ when [ambiguous] (spec nameAmbiguous). Recomposes when the @ arrives. */
@Composable
fun memberLabel(name: String, userId: String, ambiguous: Boolean): String {
    if (!ambiguous) return name
    val resolver = LocalBipeHandles.current ?: return MemberNames.label(name, userId, null)
    LaunchedEffect(userId) { resolver.resolve(userId) }
    val handles = resolver.handles.collectAsState().value
    return MemberNames.label(name, userId, handles[userId])
}

