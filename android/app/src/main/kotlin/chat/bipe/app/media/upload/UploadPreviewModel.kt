// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.upload

import android.content.Context
import android.text.format.Formatter
import chat.bipe.app.media.DataUseMeter
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.media.BipeImageDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import java.io.File
import java.util.UUID

/** Type PickedMedia of spec upload-preview. */
data class PickedMedia(
    val kind: Kind,
    val filename: String,
    val sizeLabel: String,
    val isTooLarge: Boolean,
    /** The picked file, copied into the app cache. */
    val localFile: File,
    val size: Long,
    /** image only: the sender's description for screen readers (bipe-chat/bipe#501). */
    val description: String = "",
    val id: String = UUID.randomUUID().toString(),
) {
    enum class Kind { IMAGE, VIDEO, FILE }

    companion object {
        /** The kind from the file type: images and videos go as such, everything else as a file. */
        fun kind(mimeType: String?): Kind = when {
            mimeType == null -> Kind.FILE
            mimeType.startsWith("image/") -> Kind.IMAGE
            mimeType.startsWith("video/") -> Kind.VIDEO
            else -> Kind.FILE
        }
    }
}

/** Spec upload-preview (attachment-preview of the composer spec is the same screen). */
class UploadPreviewModel(
    private val context: Context,
    files: List<Pair<File, String?>>,
    private val client: Client,
    private val timeline: Timeline,
    val replyToEventId: String?,
    private val scope: CoroutineScope,
) {
    private val _items = MutableStateFlow(
        files.take(MAX_ITEMS).map { (file, mime) ->
            PickedMedia(
                kind = PickedMedia.kind(mime ?: MediaPreparation.mimeType(file)),
                filename = file.name,
                sizeLabel = Formatter.formatShortFileSize(context, file.length()),
                isTooLarge = false,
                localFile = file,
                size = file.length(),
            )
        },
    )
    val items: StateFlow<List<PickedMedia>> = _items.asStateFlow()
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()
    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()
    private val _maxUploadLabel = MutableStateFlow<String?>(null)
    val maxUploadLabel: StateFlow<String?> = _maxUploadLabel.asStateFlow()
    private val _isClosed = MutableStateFlow(false)
    val isClosed: StateFlow<Boolean> = _isClosed.asStateFlow()

    val quality = MediaQuality.current(context)

    fun canSend(items: List<PickedMedia>, preparing: Boolean): Boolean =
        items.isNotEmpty() && !preparing && items.none { it.isTooLarge }

    /** Reads the homeserver upload limit and marks items over it. */
    fun appear() {
        scope.launch {
            val limit = runCatching { client.getMaxMediaUploadSize() }.getOrNull()?.toLong() ?: return@launch
            if (limit <= 0) return@launch
            _maxUploadLabel.value = Formatter.formatShortFileSize(context, limit)
            _items.value = _items.value.map { it.copy(isTooLarge = it.size > limit) }
        }
    }

    /** Action editCaption. */
    fun editCaption(text: String) {
        _caption.value = text
    }

    /** Action editDescription: the description of one image item, cut at the registry limit. */
    fun editDescription(index: Int, text: String) {
        val list = _items.value
        if (index !in list.indices || list[index].kind != PickedMedia.Kind.IMAGE) return
        _items.value = list.mapIndexed { i, item ->
            if (i == index) item.copy(description = text.take(BipeRegistries.IMAGE_DESCRIPTION_MAX_LENGTH)) else item
        }
    }

    /** Action remove: the screen closes when none is left. */
    fun remove(index: Int) {
        val list = _items.value
        if (index !in list.indices) return
        _items.value = list.filterIndexed { i, _ -> i != index }
        if (_items.value.isEmpty()) _isClosed.value = true
    }

    /** Action cancel: closes without sending; the composer draft stays. */
    fun cancel() {
        _isClosed.value = true
    }

    /**
     * Action send: each item as an attachment with its thumbnail and info, the caption on the first
     * item, an image's description in its content (bipe-chat/bipe#501), as a reply when replyToEventId is set,
     * then closes. The send queue keeps the uploads.
     */
    fun send() {
        if (!canSend(_items.value, _isPreparing.value)) return
        _isPreparing.value = true
        val caption = _caption.value.takeIf { it.isNotBlank() }
        scope.launch {
            val prepared = _items.value.mapNotNull { item ->
                runCatching { MediaPreparation.prepare(context, item.localFile, item.kind, quality) }.getOrNull()
                    ?.let { it to item.description.takeIf { item.kind == PickedMedia.Kind.IMAGE } }
            }
            _isPreparing.value = false
            prepared.forEachIndexed { index, (media, description) ->
                val params = UploadParameters(
                    source = UploadSource.File(media.file.path),
                    caption = if (index == 0) caption else null,
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = if (index == 0) replyToEventId else null,
                    extraContentJson = BipeImageDescription.extraContentJson(description),
                )
                val thumbnail = media.thumbnail?.let { UploadSource.File(it.path) }
                // Spec data-use: the upload counts on the network in use now.
                DataUseMeter.recordSent(media.file.length() + (media.thumbnail?.length() ?: 0L))
                runCatching {
                    when (val info = media.info) {
                        is PreparedMedia.Info.Image -> timeline.sendImage(params, thumbnail, info.info)
                        is PreparedMedia.Info.Video -> timeline.sendVideo(params, thumbnail, info.info)
                        is PreparedMedia.Info.File -> timeline.sendFile(params, info.info)
                    }
                }
            }
            _isClosed.value = true
        }
    }

    companion object {
        /** At most 10 items; each is sent as its own message. */
        const val MAX_ITEMS = 10
    }
}
