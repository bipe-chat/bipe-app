// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.chat.MessageMedia
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage

/** Display of spec timeline-media, without placeholder: the desktop has no blurhash decoder yet. */
private sealed interface PictureState {
    data object Hidden : PictureState
    data object Loading : PictureState
    data class Shown(val image: ImageBitmap) : PictureState
    data object Failed : PictureState
}

/**
 * A timeline media row (spec timeline-media, voice-message): the picture when it may show, else a file card.
 * [revealed] is the click that shows this one message (tapToShow). [save] asks where to save and saves it.
 */
@Composable internal fun MediaBody(media: MessageMedia, mayShow: Boolean, revealed: Boolean, reveal: () -> Unit,
                                  loader: AccountMedia, save: (Path) -> Unit, s: DesktopCatalog, colors: DesktopColors,
                                  open: (() -> Unit)? = null) {
    val label = media.description?.let { s.text("bipe_app_media_image_described", it) }
        ?: s.text(s.mediaKey(media.kind))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val preview = media.previewJson
        if (preview != null) MediaPicture(media, preview, mayShow || revealed, reveal, loader, label, s, colors, open)
        else {
            MediaFileCard(media, label, s, colors)
            OpenOriginalMedia(media, open, s)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mediaDetails(media, s.locale)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors["ink.secondary"]) }
            TextButton(onClick = { chooseDestination(media.filename, s.text("bipe_app_save"))?.let(save) }) {
                Icon(sharedIcon("download-simple"), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.text("bipe_app_save"))
            }
        }
    }
}

@Composable private fun MediaPicture(media: MessageMedia, sourceJson: String, shows: Boolean, reveal: () -> Unit, loader: AccountMedia,
                                     label: String, s: DesktopCatalog, colors: DesktopColors, open: (() -> Unit)?) {
    val maxWidth = if (media.kind == MessageMedia.Kind.STICKER) 160.dp else 320.dp
    val width = if (media.aspectRatio >= 1f) maxWidth else maxWidth * media.aspectRatio.coerceAtLeast(0.5f)
    var attempt by remember(sourceJson) { mutableStateOf(0) }
    // Re-key the state itself, so a reused row never shows another message's picture for a frame.
    val state by key(loader, sourceJson, shows, attempt) { produceState<PictureState>(if (shows) PictureState.Loading else PictureState.Hidden) {
        if (!shows) return@produceState
        value = try {
            val bytes = loader.eventPicture(sourceJson)
            val image = bytes?.let { withContext(Dispatchers.Default) { decodePicture(it) } }
            if (image == null) PictureState.Failed else PictureState.Shown(image)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { PictureState.Failed }
    } }
    Box(Modifier.width(width).aspectRatio(media.aspectRatio).clip(RoundedCornerShape(12.dp)).background(colors["surface.raised"])
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        when (val current = state) {
            is PictureState.Shown -> Image(current.image, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(
                if (open == null) Modifier else Modifier.clickable(role = Role.Button, onClick = open)))
            PictureState.Loading -> Text(s.text("bipe_web_loading"), style = MaterialTheme.typography.bodySmall, color = colors["ink.secondary"])
            PictureState.Hidden -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(sharedIcon(mediaIcon(media.kind)), contentDescription = null, tint = colors["glyph.secondary"], modifier = Modifier.size(28.dp))
                TextButton(onClick = reveal) { Text(s.text("bipe_desktop_media_show")) }
            }
            PictureState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.text("bipe_error_generic"), style = MaterialTheme.typography.bodySmall, color = colors["ink.secondary"])
                TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
            }
        }
    }
    if (state == PictureState.Failed) OpenOriginalMedia(media, open, s)
}

@Composable private fun OpenOriginalMedia(media: MessageMedia, open: (() -> Unit)?, s: DesktopCatalog) {
    if (open == null) return
    val label = if (media.kind == MessageMedia.Kind.VIDEO) "bipe_app_voice_play" else "bipe_avatar_open"
    TextButton(onClick = open) { Text(s.text(label)) }
}

@Composable private fun MediaFileCard(media: MessageMedia, label: String, s: DesktopCatalog, colors: DesktopColors) {
    Row(Modifier.widthIn(min = 200.dp, max = 320.dp).clip(RoundedCornerShape(12.dp)).background(colors["surface.raised"]).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(sharedIcon(mediaIcon(media.kind)), contentDescription = label, tint = colors["accent.ink"], modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(if (media.kind == MessageMedia.Kind.VOICE) label else media.filename,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun mediaIcon(kind: MessageMedia.Kind): String = when (kind) {
    MessageMedia.Kind.IMAGE -> "image"
    MessageMedia.Kind.VIDEO -> "video-camera"
    MessageMedia.Kind.STICKER -> "sticker"
    MessageMedia.Kind.VOICE, MessageMedia.Kind.AUDIO -> "microphone"
    MessageMedia.Kind.FILE -> "file"
}

/** Size and duration under the media: "2,4 MB · 0:42". The locale's own decimal mark. */
internal fun mediaDetails(media: MessageMedia, locale: String): String? {
    val parts = listOfNotNull(media.durationMillis?.let(::durationLabel), media.size?.let { sizeLabel(it, Locale.forLanguageTag(locale)) })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun durationLabel(millis: Long): String {
    val total = ((millis + 500) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun sizeLabel(bytes: Long, locale: Locale): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (unit == 0 || value >= 10) 0 else 1 }
    return "${format.format(value)} ${units[unit]}"
}

/** Decodes and scales to at most 1024 pixels on the long side, so a large photo cannot hold tens of MB per row. */
internal fun decodePicture(bytes: ByteArray, maxSide: Int = 1024): ImageBitmap? {
    val decoded = runCatching { SkiaImage.makeFromEncoded(bytes) }.getOrNull() ?: return null
    try {
        if (decoded.width !in 1..16_384 || decoded.height !in 1..16_384) return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap()
        if (!bitmap.allocN32Pixels(width, height)) { bitmap.close(); return null }
        Canvas(bitmap).use { it.drawImageRect(decoded, Rect.makeWH(width.toFloat(), height.toFloat())) }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    } finally { decoded.close() }
}

/** The Windows save dialog, with the file name from the event without any folder part. Null when the person cancels. */
internal fun chooseDestination(filename: String, title: String): Path? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = safeFileName(filename)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(directory, file).toAbsolutePath()
}

/** A sender controls the name: keep the last path part and drop characters Windows does not allow. */
internal fun safeFileName(filename: String): String {
    val last = filename.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = last.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimEnd('.')
    return cleaned.take(200).ifEmpty { "bipe" }
}
