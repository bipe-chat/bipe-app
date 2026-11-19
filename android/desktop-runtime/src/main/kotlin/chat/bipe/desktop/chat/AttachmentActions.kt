// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.shared.media.BipeImageDescription
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.*

sealed interface AttachmentInput {
    data class File(val path: Path) : AttachmentInput
    data class Clipboard(val image: Image) : AttachmentInput
}

class AttachmentActions internal constructor(
    private val client: Client,
    private val owner: AccountTaskGroup,
    private val room: () -> Room,
    private val timeline: () -> Timeline,
    private val composer: RoomComposer,
) {
    enum class Kind { IMAGE, VIDEO, FILE }
    data class Item(val input: AttachmentInput, val filename: String, val mime: String, val kind: Kind, val bytes: Long)
    data class Batch(val items: List<Item>, val limit: Long)
    data class Draft(val item: Item, val description: String = "", val failed: Boolean = false)
    data class Outcome(val remaining: List<Draft>, val caption: String, val reply: String?)

    private val sending = Mutex()

    suspend fun inspect(inputs: List<AttachmentInput>): Batch {
        val task = owner.async {
            val limit = client.getMaxMediaUploadSize().coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
            val items = inputs.distinct().take(MAX_ITEMS).map { input ->
                when (input) {
                    is AttachmentInput.Clipboard -> Item(input, "clipboard.jpg", "image/jpeg", Kind.IMAGE, 0)
                    is AttachmentInput.File -> {
                        val path = input.path
                        check(path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS))
                        val name = path.fileName.toString()
                        val mime = Files.probeContentType(path) ?: when (name.substringAfterLast('.').lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            "mp4", "m4v" -> "video/mp4"
                            "mov" -> "video/quicktime"
                            "webm" -> "video/webm"
                            else -> "application/octet-stream"
                        }
                        val kind = when {
                            mime.startsWith("image/") -> Kind.IMAGE
                            mime.startsWith("video/") -> Kind.VIDEO
                            else -> Kind.FILE
                        }
                        Item(input, name, mime, kind, Files.size(path))
                    }
                }
            }
            check(items.isNotEmpty())
            Batch(items, limit)
        }
        try { return task.await() } finally { task.cancel() }
    }

    suspend fun preview(item: Item): ByteArray {
        check(item.kind == Kind.IMAGE)
        val task = owner.async { jpeg(scaled(readImage(item.input), 500)) }
        try { return task.await() } finally { task.cancel() }
    }

    /** A failed item stays in the preview. The first accepted queue entry consumes the caption and reply. */
    suspend fun send(batch: Batch, drafts: List<Draft>, caption: String, reply: String?): Outcome = owner.async {
        sending.withLock {
            check(room().membership() == Membership.JOINED && drafts.isNotEmpty() && drafts.all { it.item in batch.items })
            val failed = mutableListOf<Draft>()
            var nextCaption = caption
            var nextReply = reply
            for (draft in drafts) {
                try {
                    prepareAndSend(draft, batch.limit, nextCaption, nextReply)
                    if (nextReply != null) composer.acceptedAttachmentReply(nextReply)
                    nextCaption = ""
                    nextReply = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += draft.copy(failed = true) }
            }
            Outcome(failed, nextCaption, nextReply)
        }
    }.await()

    private fun prepareAndSend(draft: Draft, limit: Long, caption: String, reply: String?) {
        val item = draft.item
        val image = if (item.kind == Kind.IMAGE) readImage(item.input) else null
        val cropped = image?.let { scaled(it, 2048) }
        val source = if (cropped != null) {
            val bytes = jpeg(cropped)
            check(bytes.size.toLong() <= limit)
            UploadSource.Data(bytes, item.filename.substringBeforeLast('.') + ".jpg")
        } else {
            val path = (item.input as AttachmentInput.File).path
            check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == item.bytes && item.bytes <= limit)
            UploadSource.File(path.toString())
        }
        val params = UploadParameters(source, caption.trim().takeIf { it.isNotEmpty() }, null, null, reply,
            if (cropped != null) BipeImageDescription.extraContentJson(draft.description) else null)
        owner.requireCurrent()
        val handle = when (item.kind) {
            Kind.IMAGE -> {
                checkNotNull(cropped)
                val preview = scaled(cropped, 320)
                val thumbnail = jpeg(preview)
                val info = ImageInfo(cropped.height.toULong(), cropped.width.toULong(), "image/jpeg",
                    (source as UploadSource.Data).bytes.size.toULong(),
                    ThumbnailInfo(preview.height.toULong(), preview.width.toULong(), "image/jpeg", thumbnail.size.toULong()),
                    null, null, false)
                try { timeline().sendImage(params, UploadSource.Data(thumbnail, "thumbnail.jpg"), info) }
                finally { info.destroy() }
            }
            Kind.VIDEO -> {
                val info = VideoInfo(null, null, null, item.mime, item.bytes.toULong(), null, null, null)
                try { timeline().sendVideo(params, null, info) } finally { info.destroy() }
            }
            Kind.FILE -> {
                val info = FileInfo(item.mime, item.bytes.toULong(), null, null)
                try { timeline().sendFile(params, info) } finally { info.destroy() }
            }
        }
        // The SDK owns the queued upload and retry handle after this handoff.
        handle.destroy()
    }

    private fun readImage(input: AttachmentInput): Image = when (input) {
        is AttachmentInput.Clipboard -> input.image
        is AttachmentInput.File -> {
            check(Files.isRegularFile(input.path, NOFOLLOW_LINKS))
            ImageIO.createImageInputStream(input.path.toFile()).use { stream ->
                checkNotNull(stream)
                val readers = ImageIO.getImageReaders(stream)
                check(readers.hasNext())
                val reader = readers.next()
                try {
                    reader.setInput(stream, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    check(width in 1..16384 && height in 1..16384)
                    val step = (maxOf(width, height) / 2048).coerceAtLeast(1)
                    reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
                } finally { reader.dispose() }
            }
        }
    }

    private fun scaled(image: Image, edge: Int): BufferedImage {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        check(width > 0 && height > 0)
        val ratio = minOf(1.0, edge.toDouble() / maxOf(width, height))
        val output = BufferedImage((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, output.width, output.height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            check(graphics.drawImage(image, 0, 0, output.width, output.height, null))
        } finally { graphics.dispose() }
        return output
    }

    private fun jpeg(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { stream ->
        check(ImageIO.write(image, "jpeg", stream))
        stream.toByteArray()
    }

    companion object { const val MAX_ITEMS = 10 }
}
