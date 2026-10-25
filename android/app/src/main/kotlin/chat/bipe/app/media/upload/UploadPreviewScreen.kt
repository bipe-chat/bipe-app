// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.upload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.media.mediaScrim
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeDarkColors
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Timeline
import java.io.File

/** Spec upload-preview: the picked photos, videos or file full size, a caption field and Send. */
@Composable
fun UploadPreviewScreen(
    files: List<Pair<File, String?>>,
    client: Client,
    timeline: Timeline,
    replyToEventId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(files) { UploadPreviewModel(context.applicationContext, files, client, timeline, replyToEventId, scope) }
    val items by model.items.collectAsState()
    val caption by model.caption.collectAsState()
    val isPreparing by model.isPreparing.collectAsState()
    val maxUploadLabel by model.maxUploadLabel.collectAsState()
    val isClosed by model.isClosed.collectAsState()
    val colors = BipeTheme.colors
    val canSend = model.canSend(items, isPreparing)
    val pager = rememberPagerState { items.size }

    LaunchedEffect(model) { model.appear() }
    LaunchedEffect(isClosed) { if (isClosed) onClose() }

    Dialog(onDismissRequest = model::cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceBase)
                .safeDrawingPadding()
                .imePadding()
                .testTag(BipeScreen.UPLOAD_PREVIEW.id),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = model::cancel) {
                    Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk)
                }
                Spacer(Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    IconButton(onClick = { model.remove(pager.currentPage.coerceAtMost(items.size - 1)) }) {
                        Icon(
                            painterResource(PhosphorIcon.TRASH.drawable),
                            contentDescription = stringResource(R.string.bipe_catalog_remove),
                            tint = colors.glyphPrimary,
                        )
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { items.getOrNull(it)?.id ?: it }) { page ->
                items.getOrNull(page)?.let { PickedMediaPage(it, maxUploadLabel) }
            }
            val current = items.getOrNull(pager.currentPage)
            if (current?.kind == PickedMedia.Kind.IMAGE) {
                ImageDescriptionField(current.description) { model.editDescription(pager.currentPage, it) }
            }
            if (items.size > 1) {
                val position = listOf(pager.currentPage + 1, items.size).joinToString(" / ")
                Text(
                    position,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val placeholder = stringResource(R.string.bipe_app_upload_caption_placeholder)
                BasicTextField(
                    value = caption,
                    onValueChange = model::editCaption,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                    cursorBrush = SolidColor(colors.accentFill),
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.surfaceSunken, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (caption.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                            }
                            inner()
                        }
                    },
                )
                IconButton(
                    onClick = model::send,
                    enabled = canSend,
                    modifier = Modifier.background(if (canSend) colors.accentFill else colors.surfaceSunken, CircleShape),
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(color = colors.inkOnAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable),
                            contentDescription = stringResource(R.string.bipe_app_upload_send),
                            tint = if (canSend) colors.inkOnAccent else colors.inkDisabled,
                        )
                    }
                }
            }
            if (isPreparing) {
                Text(
                    stringResource(R.string.bipe_app_upload_preparing),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PickedMediaPage(item: PickedMedia, maxUploadLabel: String?) {
    val colors = BipeTheme.colors
    val preview by produceState<ImageBitmap?>(null, item.id) { value = preview(item) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (item.kind) {
            PickedMedia.Kind.IMAGE, PickedMedia.Kind.VIDEO -> Box(modifier = Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
                val bitmap = preview
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit)
                    if (item.kind == PickedMedia.Kind.VIDEO) {
                        Icon(
                            painterResource(PhosphorIcon.PLAY.drawable),
                            contentDescription = null,
                            tint = BipeDarkColors.inkPrimary,
                            modifier = Modifier.mediaScrim(CircleShape).padding(14.dp).size(26.dp),
                        )
                    }
                } else {
                    CircularProgressIndicator(color = colors.accentFill)
                }
            }
            PickedMedia.Kind.FILE -> {
                Icon(painterResource(PhosphorIcon.FILE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(56.dp))
                Text(item.filename, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, textAlign = TextAlign.Center)
                Text(item.sizeLabel, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
        if (item.isTooLarge && maxUploadLabel != null) {
            Text(
                stringResource(R.string.bipe_app_upload_too_large, maxUploadLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.trustDangerInk,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private suspend fun preview(item: PickedMedia): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap: Bitmap? = when (item.kind) {
            PickedMedia.Kind.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.localFile.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                BitmapFactory.decodeFile(item.localFile.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            PickedMedia.Kind.VIDEO -> MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(item.localFile.path)
                    retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            }
            PickedMedia.Kind.FILE -> null
        }
        bitmap?.asImageBitmap()
    }.getOrNull()
}

/** The description of the image on screen (spec upload-preview, bipe-chat/bipe#501). */
@Composable
private fun ImageDescriptionField(text: String, onChange: (String) -> Unit) {
    val colors = BipeTheme.colors
    val label = stringResource(R.string.bipe_app_upload_description)
    val footer = stringResource(R.string.bipe_app_upload_description_footer)
    val placeholder = stringResource(R.string.bipe_app_upload_description_placeholder)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
        BasicTextField(
            value = text,
            onValueChange = onChange,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
            cursorBrush = SolidColor(colors.accentFill),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(colors.surfaceSunken, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = footer
                },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                    }
                    inner()
                }
            },
        )
        Text(
            footer,
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {},
        )
    }
}
