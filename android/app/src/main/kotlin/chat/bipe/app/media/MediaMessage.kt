// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MediaSource
import chat.bipe.stickers.StickerPackRef
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind

/** A media source of an event. The SDK type is an object; rows compare it by its URL. */
class MediaRef(val source: MediaSource) {
    val url: String = source.url()

    override fun equals(other: Any?): Boolean = other is MediaRef && other.url == url

    override fun hashCode(): Int = url.hashCode()
}

/** Type MediaMessage of spec timeline-media. */
data class MediaMessage(
    val kind: Kind,
    val filename: String,
    val caption: String? = null,
    val mimeType: String? = null,
    /** Size in bytes; the row formats it with the system formatter. */
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationLabel: String? = null,
    val blurhash: String? = null,
    /** Own media while sending: percent 0 to 100. */
    val uploadProgress: Int? = null,
    val isAnimated: Boolean = false,
    /** info.is_animated as sent; null when the sender did not say. */
    val animationHint: Boolean? = null,
    val source: MediaRef,
    val thumbnailSource: MediaRef? = null,
    /** image only: the sender's description for screen readers (bipe-chat/bipe#501, registries/image-descriptions.json). */
    val description: String? = null,
    /** sticker only: the room pack of stickerPackField (message-actions viewStickerPack, ADR 0205). */
    val stickerPack: StickerPackRef? = null,
) {
    enum class Kind { IMAGE, VIDEO, FILE, STICKER }

    /** Width over height for the row box. Without size: 4:3 (spec timeline-media notes). */
    val aspectRatio: Float
        get() = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 4f / 3f

    /** The accessibility label and preview text (spec timeline-media notes). */
    @get:StringRes
    val kindLabel: Int
        get() = when (kind) {
            Kind.IMAGE -> R.string.bipe_app_media_image
            Kind.VIDEO -> R.string.bipe_app_media_video
            Kind.FILE -> R.string.bipe_app_media_file
            Kind.STICKER -> R.string.bipe_app_media_sticker
        }

    /** A sticker that can be an animated WebP (ADR 0205): said so by the sender, or a WebP that did not say. */
    val mayBeAnimated: Boolean
        get() = kind == Kind.STICKER && (animationHint ?: (mimeType == null || mimeType == "image/webp"))

    /** image, video and sticker open media-viewer; a file opens the system preview. */
    val opensInViewer: Boolean get() = kind != Kind.FILE

    fun sizeLabel(context: Context): String? = size?.let { Formatter.formatShortFileSize(context, it) }
}

/** Type VoiceMessage of spec voice-message. Playback state lives in VoicePlayer. */
data class VoiceMessage(
    val isVoice: Boolean,
    val filename: String,
    val durationMillis: Long,
    /** One bar per entry, 0 to 1024. Empty draws a flat line. */
    val waveform: List<Int>,
    /** The caption of a voice message: the sender's transcript (ADR 0023). */
    val transcript: String?,
    val mimeType: String?,
    val source: MediaRef,
    /** The count of the forward mark in the event content (bipe-chat/bipe#83); 0 when it has none. */
    val forwardCount: Int = 0,
) {
    val durationLabel: String get() = MediaFormat.duration(durationMillis)
}

/** What a message event holds, for the media rows. */
sealed interface MediaContent {
    data class Media(val media: MediaMessage) : MediaContent

    data class Voice(val voice: VoiceMessage) : MediaContent

    companion object {
        fun of(kind: MsgLikeKind): MediaContent? = when (kind) {
            is MsgLikeKind.Message -> of(kind.content.msgType)
            is MsgLikeKind.Sticker -> Media(image(kind.info, kind.body, null, kind.source).copy(kind = MediaMessage.Kind.STICKER))
            else -> null
        }

        fun of(type: MessageType): MediaContent? = when (type) {
            is MessageType.Image -> type.content.let { Media(image(it.info, it.filename, it.caption, it.source)) }
            is MessageType.Video -> type.content.let {
                val info = it.info
                Media(
                    MediaMessage(
                        kind = MediaMessage.Kind.VIDEO,
                        filename = it.filename,
                        caption = it.caption,
                        mimeType = info?.mimetype,
                        size = info?.size?.toLong(),
                        width = info?.width?.toInt(),
                        height = info?.height?.toInt(),
                        durationLabel = info?.duration?.let { d -> MediaFormat.duration(d.toMillis()) },
                        blurhash = info?.blurhash,
                        source = MediaRef(it.source),
                        thumbnailSource = info?.thumbnailSource?.let(::MediaRef),
                    ),
                )
            }
            is MessageType.File -> type.content.let {
                val info = it.info
                Media(
                    MediaMessage(
                        kind = MediaMessage.Kind.FILE,
                        filename = it.filename,
                        caption = it.caption,
                        mimeType = info?.mimetype,
                        size = info?.size?.toLong(),
                        source = MediaRef(it.source),
                        thumbnailSource = info?.thumbnailSource?.let(::MediaRef),
                    ),
                )
            }
            is MessageType.Audio -> type.content.let {
                // The voice marker makes a voice message; its caption is the transcript (ADR 0023).
                val isVoice = it.voice != null
                Voice(
                    VoiceMessage(
                        isVoice = isVoice,
                        filename = it.filename,
                        durationMillis = (it.audio?.duration ?: it.info?.duration)?.toMillis() ?: 0L,
                        waveform = it.audio?.waveform?.map { bar -> bar.toInt() }.orEmpty(),
                        transcript = if (isVoice) it.caption?.takeIf { c -> c.isNotBlank() } else null,
                        mimeType = it.info?.mimetype,
                        source = MediaRef(it.source),
                    ),
                )
            }
            else -> null
        }

        private fun image(info: ImageInfo?, filename: String, caption: String?, source: MediaSource) = MediaMessage(
            kind = MediaMessage.Kind.IMAGE,
            filename = filename,
            caption = caption,
            mimeType = info?.mimetype,
            size = info?.size?.toLong(),
            width = info?.width?.toInt(),
            height = info?.height?.toInt(),
            blurhash = info?.blurhash,
            isAnimated = info?.isAnimated ?: false,
            animationHint = info?.isAnimated,
            source = MediaRef(source),
            thumbnailSource = info?.thumbnailSource?.let(::MediaRef),
        )
    }
}

object MediaFormat {
    /** "0:42", "12:05". */
    fun duration(millis: Long): String {
        val total = ((millis + 500) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Upload percent of an own media message while it sends. */
    fun uploadPercent(state: EventSendState?): Int? {
        val notSent = state as? EventSendState.NotSentYet ?: return null
        val progress = notSent.progress?.progress ?: return 0
        if (progress.total == 0uL) return 0
        return (progress.current * 100uL / progress.total).toInt().coerceAtMost(100)
    }
}
