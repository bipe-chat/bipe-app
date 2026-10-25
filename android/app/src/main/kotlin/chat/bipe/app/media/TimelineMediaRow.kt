// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeDarkColors
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch

/** Display of spec timeline-media. */
enum class MediaDisplay { PLACEHOLDER, TAP_TO_SHOW, LOADING, SHOWN, FAILED }

private val MAX_WIDTH = 260.dp
private val STICKER_WIDTH = 160.dp

/**
 * Spec timeline-media: the content of an image, video, sticker or file message. The bubble, sender,
 * reply quote, footer and reactions come from the message row of spec timeline-rows.
 */
@Composable
fun TimelineMediaRow(
    message: TimelineMessage,
    media: MediaMessage,
    loader: MediaLoader,
    isPrivateRoom: Boolean,
    onOpenViewer: () -> Unit,
    onCancelUpload: () -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var display by remember(media.source) { mutableStateOf(MediaDisplay.PLACEHOLDER) }
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    var image by remember(media.source) { mutableStateOf<LoadedImage?>(null) }
    var openingFile by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val sending = media.uploadProgress != null && message.isOwn && message.sendState == TimelineMessage.SendState.SENDING

    // Action appear: loads the thumbnail (or the image) when display may show it.
    LaunchedEffect(media.source, revealed, attempt) {
        if (media.kind == MediaMessage.Kind.FILE) return@LaunchedEffect
        // Spec data-use: on mobile data with the data saver, photos and stickers wait for a tap.
        val savesData = (media.kind == MediaMessage.Kind.IMAGE || media.kind == MediaMessage.Kind.STICKER) && DataUseMeter.savesData(context)
        if (!revealed && !message.isOwn && (!currentShowMedia(context).shows(isPrivateRoom) || savesData)) {
            display = MediaDisplay.TAP_TO_SHOW
            return@LaunchedEffect
        }
        if (display == MediaDisplay.SHOWN) return@LaunchedEffect
        display = if (media.blurhash == null) MediaDisplay.LOADING else MediaDisplay.PLACEHOLDER
        val loaded = loader.previewImage(media, if (media.kind == MediaMessage.Kind.STICKER) 480 else 900)
        image = loaded
        display = if (loaded != null) MediaDisplay.SHOWN else MediaDisplay.FAILED
    }

    fun openFile() {
        if (openingFile) return
        openingFile = true
        scope.launch {
            val file = runCatching { loader.file(context, media.source, media.filename, media.mimeType) }.getOrNull()
            openingFile = false
            if (file != null) {
                display = MediaDisplay.PLACEHOLDER
                runCatching { MediaIntents.open(context, file, media.mimeType) }
            } else {
                display = MediaDisplay.FAILED
            }
        }
    }

    fun tap() {
        when {
            sending -> onCancelUpload()
            // Action tapToShow.
            display == MediaDisplay.TAP_TO_SHOW -> revealed = true
            // Action retryDownload.
            display == MediaDisplay.FAILED && media.kind != MediaMessage.Kind.FILE -> attempt++
            media.opensInViewer -> onOpenViewer()
            else -> openFile()
        }
    }

    Column(
        modifier = Modifier.testTag(BipeScreen.TIMELINE_MEDIA.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (media.kind == MediaMessage.Kind.FILE) {
            FileCard(media, display, openingFile, if (sending) media.uploadProgress else null, ::tap)
        } else {
            Visual(media, display, image, if (sending) media.uploadProgress else null, ::tap)
        }
        val caption = media.caption?.takeIf { it.isNotBlank() }
        if (caption != null && media.kind != MediaMessage.Kind.STICKER) {
            Text(caption, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.width(MAX_WIDTH))
        }
    }
}

@Composable
private fun Visual(media: MediaMessage, display: MediaDisplay, image: LoadedImage?, uploadProgress: Int?, onTap: () -> Unit) {
    val colors = BipeTheme.colors
    val isSticker = media.kind == MediaMessage.Kind.STICKER
    val width = if (isSticker) STICKER_WIDTH else MAX_WIDTH
    val height = minOf(width / media.aspectRatio, width * 1.4f)
    val description = mediaAccessibilityLabel(media)
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(if (isSticker) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp))
            .then(if (isSticker || display == MediaDisplay.SHOWN) Modifier else Modifier.background(colors.surfaceSunken))
            .clickable(onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val blurhash = media.blurhash
        if (display != MediaDisplay.SHOWN && blurhash != null) {
            val placeholder = remember(blurhash) { Blurhash.bitmap(blurhash)?.asImageBitmap() }
            if (placeholder != null) {
                Image(placeholder, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
        }
        if (display == MediaDisplay.SHOWN && image != null) {
            val animated = image.animated
            if (animated != null) {
                AndroidView(
                    factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { view ->
                        view.setImageDrawable(animated)
                        (animated as? android.graphics.drawable.Animatable)?.start()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    image.bitmap,
                    contentDescription = null,
                    contentScale = if (isSticker) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Overlay(media, display, uploadProgress)
    }
}

@Composable
private fun BoxScope.Overlay(media: MediaMessage, display: MediaDisplay, uploadProgress: Int?) {
    val ink = BipeDarkColors.inkPrimary
    if (uploadProgress != null) {
        Box(modifier = Modifier.size(44.dp).mediaScrim(CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { uploadProgress / 100f }, color = ink, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
            Icon(
                painterResource(PhosphorIcon.X.drawable),
                contentDescription = stringResource(R.string.bipe_app_cancel),
                tint = ink,
                modifier = Modifier.size(16.dp),
            )
        }
        return
    }
    when (display) {
        MediaDisplay.TAP_TO_SHOW -> Text(
            stringResource(R.string.bipe_app_media_tap_to_show),
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            modifier = Modifier.mediaScrim(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        MediaDisplay.PLACEHOLDER, MediaDisplay.LOADING -> Box(modifier = Modifier.mediaScrim(CircleShape).padding(10.dp)) {
            CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        MediaDisplay.FAILED -> Column(
            modifier = Modifier.padding(8.dp).mediaScrim(RoundedCornerShape(10.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(PhosphorIcon.ARROW_CLOCKWISE.drawable), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.bipe_app_media_failed), style = MaterialTheme.typography.labelSmall, color = ink, textAlign = TextAlign.Center)
        }
        MediaDisplay.SHOWN -> if (media.kind == MediaMessage.Kind.VIDEO) {
            Icon(
                painterResource(PhosphorIcon.PLAY.drawable),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.mediaScrim(CircleShape).padding(12.dp).size(22.dp),
            )
            media.durationLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .mediaScrim(RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FileCard(media: MediaMessage, display: MediaDisplay, opening: Boolean, uploadProgress: Int?, onTap: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val label = stringResource(media.kindLabel)
    Row(
        modifier = Modifier
            .width(MAX_WIDTH)
            .clickable(onClick = onTap)
            .semantics { contentDescription = "$label, ${media.filename}" }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(40.dp).background(colors.accentWash, CircleShape), contentAlignment = Alignment.Center) {
            when {
                opening -> CircularProgressIndicator(color = colors.accentFill, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                uploadProgress != null -> {
                    CircularProgressIndicator(progress = { uploadProgress / 100f }, color = colors.accentFill, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_app_cancel), tint = colors.accentInk, modifier = Modifier.size(16.dp))
                }
                else -> Icon(
                    painterResource(if (display == MediaDisplay.FAILED) PhosphorIcon.ARROW_CLOCKWISE.drawable else PhosphorIcon.FILE.drawable),
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                media.filename,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (display == MediaDisplay.FAILED) {
                Text(stringResource(R.string.bipe_app_media_failed), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
            } else {
                media.sizeLabel(context)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                }
            }
        }
    }
}

/** Light ink on a dark veil over media, in light and dark mode. */
fun Modifier.mediaScrim(shape: Shape): Modifier =
    background(BipeDarkColors.surfaceBase.copy(alpha = 0.6f), shape)

/** What TalkBack reads: the description of an image before the caption (spec timeline-media notes). */
@Composable
internal fun mediaAccessibilityLabel(media: MediaMessage): String {
    val described = media.description?.takeIf { media.kind == MediaMessage.Kind.IMAGE }
    val base = described?.let { stringResource(R.string.bipe_app_media_image_described, it) } ?: stringResource(media.kindLabel)
    return media.caption?.takeIf { it.isNotBlank() }?.let { "$base, $it" } ?: base
}
