// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeDarkColors
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Spec media-viewer: full screen photo or video, swipe through the chat's media, share and save. */
@Composable
fun MediaViewer(
    messages: List<TimelineMessage>,
    startRowId: String,
    loader: MediaLoader,
    onLoadHistory: () -> Unit,
    onClose: () -> Unit,
) {
    // The viewer is dark in both themes.
    val colors = BipeDarkColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The images and videos of the loaded timeline, oldest first.
    val items = remember(messages) { messages.filter { it.media?.opensInViewer == true } }
    var rowId by remember { mutableStateOf(startRowId) }
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startRowId }.coerceAtLeast(0)) { items.size }
    var chromeHidden by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var askGalleryPermission by remember { mutableStateOf(false) }
    val current = items.firstOrNull { it.id == rowId }

    // Older media loaded at the front: keep the page on the same row.
    LaunchedEffect(items) {
        val index = items.indexOfFirst { it.id == rowId }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            items.getOrNull(page)?.let { rowId = it.id }
            // Action swipe: at the oldest loaded media, back-paginate.
            if (page == 0) onLoadHistory()
        }
    }

    val savedText = stringResource(R.string.bipe_app_media_saved)
    val failedText = stringResource(R.string.bipe_app_media_save_failed)
    fun show(text: String) {
        notice = text
        scope.launch {
            delay(2000)
            if (notice == text) notice = null
        }
    }

    fun saveNow() {
        val media = current?.media ?: return
        working = true
        scope.launch {
            val file = runCatching { loader.file(context, media.source, media.filename, media.mimeType) }.getOrNull()
            val saved = file != null &&
                MediaIntents.saveToGallery(context, file, media.filename, media.mimeType, media.kind == MediaMessage.Kind.VIDEO)
            working = false
            show(if (saved) savedText else failedText)
        }
    }

    // Android 8 and 9 need the storage permission to add to the gallery; later versions need none.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveNow() else askGalleryPermission = true
    }

    /** Action save. */
    fun save() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else saveNow()
    }

    /** Action share: downloads the full file to the cache and opens the share sheet. */
    fun share() {
        val media = current?.media ?: return
        working = true
        scope.launch {
            val file = runCatching { loader.file(context, media.source, media.filename, media.mimeType) }.getOrNull()
            working = false
            if (file != null) runCatching { MediaIntents.share(context, file, media.mimeType) } else show(failedText)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceBase)
                .testTag(BipeScreen.MEDIA_VIEWER.id)
                .pointerInput(Unit) {
                    // Swipe down closes (action close).
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > 300f) onClose() },
                    ) { _, dragAmount -> total += dragAmount }
                },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].id }) { page ->
                val media = items[page].media
                if (media != null) {
                    MediaPage(media, loader, onTap = { chromeHidden = !chromeHidden })
                }
            }
            AnimatedVisibility(visible = !chromeHidden, enter = fadeIn(), exit = fadeOut()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .mediaScrim(RoundedCornerShape(0.dp))
                            .safeDrawingPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_app_close), tint = colors.inkPrimary)
                        }
                        if (current != null) {
                            Column {
                                Text(
                                    current.senderName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.sentAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .mediaScrim(RoundedCornerShape(0.dp))
                            .safeDrawingPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        current?.media?.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                            Text(
                                caption,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.inkPrimary,
                                modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                            IconButton(onClick = ::share, enabled = !working) {
                                Icon(painterResource(PhosphorIcon.SHARE_NETWORK.drawable), contentDescription = stringResource(R.string.bipe_app_media_share), tint = colors.inkPrimary)
                            }
                            IconButton(onClick = ::save, enabled = !working) {
                                Icon(painterResource(PhosphorIcon.DOWNLOAD_SIMPLE.drawable), contentDescription = stringResource(R.string.bipe_app_media_save), tint = colors.inkPrimary)
                            }
                        }
                    }
                }
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .mediaScrim(RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        if (askGalleryPermission) {
            AlertDialog(
                onDismissRequest = { askGalleryPermission = false },
                text = { Text(stringResource(R.string.bipe_app_media_gallery_permission)) },
                confirmButton = {
                    TextButton(onClick = {
                        askGalleryPermission = false
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
                },
                dismissButton = {
                    TextButton(onClick = { askGalleryPermission = false }) { Text(stringResource(R.string.bipe_app_cancel)) }
                },
            )
        }
    }
}

/** One photo or video of the viewer. */
@Composable
private fun MediaPage(media: MediaMessage, loader: MediaLoader, onTap: () -> Unit) {
    val colors = BipeDarkColors
    val context = LocalContext.current
    var preview by remember(media.source) { mutableStateOf<LoadedImage?>(null) }
    var full by remember(media.source) { mutableStateOf<LoadedImage?>(null) }
    var videoPath by remember(media.source) { mutableStateOf<String?>(null) }
    // isLoadingFull of spec media-viewer.
    var isLoadingFull by remember(media.source) { mutableStateOf(true) }
    var failed by remember(media.source) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(media.source, attempt) {
        failed = false
        isLoadingFull = true
        preview = loader.previewImage(media, 900)
        if (media.kind == MediaMessage.Kind.VIDEO) {
            videoPath = runCatching { loader.file(context, media.source, media.filename, media.mimeType) }.getOrNull()?.path
            failed = videoPath == null
        } else {
            full = loader.fullImage(media)
            failed = full == null
        }
        isLoadingFull = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val path = videoPath
        val image = full ?: preview
        if (path != null) {
            // Video plays with the system controls; sound on (spec media-viewer notes).
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        val controller = MediaController(viewContext)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoPath(path)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (image != null) {
            ZoomableImage(image, onTap)
        }
        if (isLoadingFull) {
            CircularProgressIndicator(color = colors.inkPrimary)
        } else if (failed) {
            Text(
                stringResource(R.string.bipe_app_media_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                modifier = Modifier
                    .mediaScrim(RoundedCornerShape(10.dp))
                    .pointerInput(Unit) { detectTapGestures { attempt++ } }
                    .padding(12.dp),
            )
        }
    }
}

/** Pinch and double tap zoom for a photo; a single tap hides the bars. */
@Composable
private fun ZoomableImage(image: LoadedImage, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    val modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offsetX = 0f
                    offsetY = 0f
                },
            )
        }
        // Pan only while zoomed, so a swipe at scale 1 goes to the pager.
        .transformable(transform, canPan = { scale > 1f })
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
    val animated = image.animated
    if (animated != null) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                view.setImageDrawable(animated)
                (animated as? android.graphics.drawable.Animatable)?.start()
            },
            modifier = modifier,
        )
    } else {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    }
}
