// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MediaPreviews
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind

/**
 * Type MediaMessage of spec timeline-media, and the voice-message rows.
 * A snapshot: sources are the SDK's JSON (which carries the decryption key), never live native objects.
 */
data class MessageMedia(
    val kind: Kind,
    val filename: String,
    val caption: String? = null,
    val mimeType: String? = null,
    /** Bytes; the row formats it. */
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val sourceJson: String,
    val thumbnailJson: String? = null,
    val description: String? = null,
) {
    enum class Kind { IMAGE, VIDEO, FILE, STICKER, AUDIO, VOICE }

    /** Width over height for the row box, so the timeline does not jump. Without size: 4:3 (spec timeline-media notes). */
    val aspectRatio: Float
        get() = if (width != null && height != null && width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.25f, 4f) else 4f / 3f

    /** The picture a row loads: the event thumbnail, else the image itself. A sticker always loads its file (ADR 0205). */
    val previewJson: String?
        get() = when (kind) {
            Kind.STICKER -> sourceJson
            Kind.IMAGE -> thumbnailJson ?: sourceJson
            Kind.VIDEO -> thumbnailJson
            Kind.FILE, Kind.AUDIO, Kind.VOICE -> null
        }

    /** The text a row, a reply quote and the chat list show: the caption, else the kind label (spec timeline-media notes). */
    fun summary(labels: ChatLabels): String = caption?.takeIf { it.isNotBlank() } ?: labels.mediaLabel(kind)

    companion object {
        fun of(kind: MsgLikeKind): MessageMedia? = when (kind) {
            is MsgLikeKind.Message -> of(kind.content.msgType)
            is MsgLikeKind.Sticker -> image(kind.info, kind.body, null, kind.source).copy(kind = Kind.STICKER)
            else -> null
        }

        fun of(type: MessageType): MessageMedia? = when (type) {
            is MessageType.Image -> type.content.let { image(it.info, it.filename, it.caption, it.source) }
            is MessageType.Video -> type.content.let {
                val info = it.info
                MessageMedia(Kind.VIDEO, it.filename, it.caption, info?.mimetype, info?.size?.toLong(),
                    info?.width?.toInt(), info?.height?.toInt(), info?.duration?.toMillis(),
                    it.source.toJson(), info?.thumbnailSource?.toJson())
            }
            is MessageType.File -> type.content.let {
                val info = it.info
                MessageMedia(Kind.FILE, it.filename, it.caption, info?.mimetype, info?.size?.toLong(),
                    sourceJson = it.source.toJson(), thumbnailJson = info?.thumbnailSource?.toJson())
            }
            is MessageType.Audio -> type.content.let {
                // The voice marker makes a voice message; its caption is the transcript (ADR 0023).
                val voice = it.voice != null
                MessageMedia(if (voice) Kind.VOICE else Kind.AUDIO, it.filename, it.caption, it.info?.mimetype, it.info?.size?.toLong(),
                    durationMillis = (it.audio?.duration ?: it.info?.duration)?.toMillis(), sourceJson = it.source.toJson())
            }
            else -> null
        }

        private fun image(info: ImageInfo?, filename: String, caption: String?, source: MediaSource) = MessageMedia(
            Kind.IMAGE, filename, caption, info?.mimetype, info?.size?.toLong(), info?.width?.toInt(), info?.height?.toInt(),
            sourceJson = source.toJson(), thumbnailJson = info?.thumbnailSource?.toJson(),
        )
    }
}

/** Setting showMedia of spec timeline-media, stored in the account as MSC4278 m.media_preview_config. */
enum class ShowMedia {
    ALWAYS, PRIVATE_ONLY, NEVER;

    fun sdk(): MediaPreviews = when (this) {
        ALWAYS -> MediaPreviews.ON
        PRIVATE_ONLY -> MediaPreviews.PRIVATE
        NEVER -> MediaPreviews.OFF
    }

    companion object {
        /** An account without the setting keeps the default, always; per the timeline-media spec. */
        fun of(previews: MediaPreviews?): ShowMedia = when (previews) {
            MediaPreviews.PRIVATE -> PRIVATE_ONLY
            MediaPreviews.OFF -> NEVER
            MediaPreviews.ON, null -> ALWAYS
        }
    }
}

/**
 * Whether a row may load its picture before a click (spec timeline-media showMedia and fromStranger).
 * A computer has no address book, and it does not read the accepted-request rooms yet, so every DM counts as
 * accepted from chat-requests: media from the other person waits for a click. Own media always shows.
 */
sealed interface MediaShowPolicy {
    fun shows(own: Boolean): Boolean

    /** Before the room facts load, only own media shows. */
    data object Unknown : MediaShowPolicy {
        override fun shows(own: Boolean) = own
    }

    data class Known(val showMedia: ShowMedia, val isDm: Boolean, val isPublic: Boolean) : MediaShowPolicy {
        override fun shows(own: Boolean) = own || (!isDm && when (showMedia) {
            ShowMedia.ALWAYS -> true
            ShowMedia.PRIVATE_ONLY -> !isPublic
            ShowMedia.NEVER -> false
        })
    }
}
