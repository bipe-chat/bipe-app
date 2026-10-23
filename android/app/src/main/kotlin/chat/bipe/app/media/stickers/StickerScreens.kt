// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.stickers.Sticker
import chat.bipe.stickers.StickerImportCandidate
import chat.bipe.stickers.StickerImporter
import chat.bipe.stickers.StickerPack
import chat.bipe.stickers.StickerPackClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client

/** The pack client of the session: the Matrix client-server API with the session's token (ADR 0033). */
fun stickerPackClient(client: Client): StickerPackClient? {
    val session = runCatching { client.session() }.getOrNull() ?: return null
    return StickerPackClient(session.homeserverUrl, session.userId) { runCatching { client.session().accessToken }.getOrNull() }
}

private enum class Screen { PICKER, IMPORT, FROM_PHOTO }

/** Spec sticker-picker: the user's pack, this chat's packs and the enabled room packs (ADR 0029, ADR 0033). */
@Composable
fun StickerPicker(client: Client, roomId: String, loader: MediaLoader, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val packClient = remember(client) { stickerPackClient(client) }
    var packs by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    // phase of spec sticker-picker: null is loading, true loaded, false failed.
    var loaded by remember { mutableStateOf<Boolean?>(null) }
    var sendFailed by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.PICKER) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt, screen) {
        if (screen != Screen.PICKER) return@LaunchedEffect
        loaded = null
        val result = packClient?.let { runCatching { it.packs(roomId) }.getOrNull() }
        if (result != null) packs = result
        loaded = result != null
    }

    /** Action send: an m.sticker event through Room.sendRaw, which encrypts in an encrypted room. Then closes. */
    fun send(sticker: Sticker) {
        sendFailed = false
        scope.launch {
            val ok = runCatching {
                val room = client.getRoom(roomId) ?: error("no room")
                room.sendRaw("m.sticker", sticker.eventContentJson())
            }.isSuccess
            if (ok) onClose() else sendFailed = true
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.surfaceBase).safeDrawingPadding()) {
            when (screen) {
                Screen.IMPORT -> StickerImport(packClient, onClose = { screen = Screen.PICKER })
                Screen.FROM_PHOTO -> StickerFromPhoto(packClient, onClose = { screen = Screen.PICKER })
                Screen.PICKER -> {
                    Header(stringResource(R.string.bipe_stickers_title), stringResource(R.string.bipe_app_close), onClose)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(BipeScreen.STICKER_PICKER.id), contentAlignment = Alignment.Center) {
                        when {
                            loaded == null -> CircularProgressIndicator(color = colors.accentFill)
                            loaded == false -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.bipe_stickers_error_load), color = colors.inkSecondary)
                                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry), color = colors.accentInk) }
                            }
                            packs.isEmpty() -> Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(R.string.bipe_stickers_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                                Text(stringResource(R.string.bipe_stickers_empty_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                            }
                            else -> LazyVerticalGrid(
                                columns = GridCells.Adaptive(76.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                packs.forEach { pack ->
                                    item(key = "title-${pack.id}", span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            packTitle(pack, roomId),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.inkSecondary,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                    items(pack.stickers, key = { it.id }) { sticker ->
                                        StickerThumbnail(sticker, loader, onClick = { send(sticker) })
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (sendFailed) {
                            Text(stringResource(R.string.bipe_app_sticker_send_failed), style = MaterialTheme.typography.labelMedium, color = colors.trustDangerInk)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { screen = Screen.FROM_PHOTO }) { Text(stringResource(R.string.bipe_stickers_from_photo), color = colors.accentInk) }
                            TextButton(onClick = { screen = Screen.IMPORT }) { Text(stringResource(R.string.bipe_stickers_import_action), color = colors.accentInk) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun packTitle(pack: StickerPack, roomId: String): String = when (val source = pack.source) {
    StickerPack.Source.User -> stringResource(R.string.bipe_stickers_pack_personal)
    is StickerPack.Source.Room -> if (source.roomId == roomId) {
        stringResource(R.string.bipe_stickers_pack_room)
    } else {
        pack.displayName ?: stringResource(R.string.bipe_stickers_pack_room)
    }
}

@Composable
private fun StickerThumbnail(sticker: Sticker, loader: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    var image by remember(sticker.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(sticker.url) { image = loader.thumbnail(sticker.url, 228) }
    val label = stringResource(R.string.bipe_a11y_sticker_send, sticker.body)
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(colors.surfaceSunken))
        }
    }
}

@Composable
private fun Header(title: String, closeLabel: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text(closeLabel, color = colors.accentInk) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.inkPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Box(Modifier.size(64.dp, 1.dp))
    }
}

/** Spec sticker-import: WhatsApp export (ZIP) or WebP and PNG files into the user's pack. */
@Composable
private fun StickerImport(packClient: StickerPackClient?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // phase of spec sticker-import.
    var phase by remember { mutableStateOf("intro") }
    var added by remember { mutableStateOf(0) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        phase = "importing"
        scope.launch {
            val candidates = withContext(Dispatchers.IO) {
                uris.flatMap { uri ->
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    StickerImporter.candidates(name) { context.contentResolver.openInputStream(uri) }
                }.distinctBy { it.shortcode }
            }
            if (candidates.isEmpty()) {
                phase = "nothingNew"
                return@launch
            }
            val result = packClient?.let { runCatching { it.addToUserPack(candidates) }.getOrNull() }
            phase = when {
                result == null -> "failed"
                result > 0 -> "done"
                else -> "nothingNew"
            }
            added = result ?: 0
        }
    }
    Header(stringResource(R.string.bipe_stickers_import_action), stringResource(R.string.bipe_app_close), onClose)
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp).testTag(BipeScreen.STICKER_IMPORT.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (phase) {
            "intro" -> {
                Text(stringResource(R.string.bipe_stickers_import_help), color = colors.inkPrimary)
                Text(stringResource(R.string.bipe_stickers_import_help_folder_android), color = colors.inkPrimary)
                Text(stringResource(R.string.bipe_stickers_import_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                Button(
                    onClick = { chooser.launch(arrayOf("application/zip", "image/webp", "image/png")) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentFill, contentColor = colors.inkOnAccent),
                ) { Text(stringResource(R.string.bipe_stickers_import_action)) }
            }
            "importing" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.bipe_stickers_import_progress), color = colors.inkPrimary)
            }
            "done" -> Text(stringResource(R.string.bipe_stickers_import_done, added.toString()), color = colors.inkPrimary)
            "nothingNew" -> Text(stringResource(R.string.bipe_stickers_import_none), color = colors.inkPrimary)
            else -> Text(stringResource(R.string.bipe_stickers_import_failed), color = colors.trustDangerInk)
        }
    }
}

/**
 * Spec sticker-from-photo: a centred square of a photo becomes a 512 by 512 WebP sticker in the user's pack.
 * No subject cut-out on Android: that needs ML Kit, a Google dependency Bipe has not decided on.
 */
@Composable
private fun StickerFromPhoto(packClient: StickerPackClient?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // phase of spec sticker-from-photo.
    var phase by remember { mutableStateOf("picking") }
    var candidate by remember { mutableStateOf<StickerImportCandidate?>(null) }
    var failure by remember { mutableStateOf(R.string.bipe_app_sticker_make_failed) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        phase = "making"
        scope.launch {
            val made = withContext(Dispatchers.Default) { decodePhoto(context, uri)?.let(StickerImporter::fromPhoto) }
            candidate = made
            if (made != null) {
                phase = "preview"
            } else {
                failure = R.string.bipe_app_sticker_make_failed
                phase = "failed"
            }
        }
    }
    LaunchedEffect(Unit) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Header(stringResource(R.string.bipe_stickers_from_photo), stringResource(R.string.bipe_app_cancel), onClose)
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp).testTag(BipeScreen.STICKER_FROM_PHOTO.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (phase) {
            "picking" -> TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(stringResource(R.string.bipe_stickers_from_photo), color = colors.accentInk)
            }
            "making" -> {
                CircularProgressIndicator(color = colors.accentFill)
                Text(stringResource(R.string.bipe_app_sticker_making), color = colors.inkSecondary)
            }
            "preview", "saving", "saved" -> {
                candidate?.let { made ->
                    val bitmap = remember(made) { BitmapFactory.decodeByteArray(made.bytes, 0, made.bytes.size)?.asImageBitmap() }
                    Box(modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        CheckerBoard()
                        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                when (phase) {
                    "saved" -> Text(stringResource(R.string.bipe_sticker_saved), color = colors.inkSecondary)
                    else -> Button(
                        onClick = onClick@{
                            val made = candidate ?: return@onClick
                            phase = "saving"
                            scope.launch {
                                val ok = packClient?.let { runCatching { it.addToUserPack(listOf(made)) }.isSuccess } ?: false
                                if (ok) {
                                    phase = "saved"
                                    delay(1000)
                                    onClose()
                                } else {
                                    failure = R.string.bipe_sticker_save_failed
                                    phase = "failed"
                                }
                            }
                        },
                        enabled = phase == "preview",
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentFill, contentColor = colors.inkOnAccent),
                    ) { Text(stringResource(R.string.bipe_action_save_sticker)) }
                }
            }
            else -> {
                Text(stringResource(failure), color = colors.trustDangerInk, textAlign = TextAlign.Center)
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.bipe_stickers_from_photo), color = colors.accentInk)
                }
            }
        }
    }
}

/** The checked background behind a sticker preview, so transparency shows. */
@Composable
private fun CheckerBoard() {
    val colors = BipeTheme.colors
    Canvas(modifier = Modifier.fillMaxSize().background(colors.surfaceRaised)) {
        val cell = 12.dp.toPx()
        var row = 0
        var y = 0f
        while (y < size.height) {
            var column = 0
            var x = 0f
            while (x < size.width) {
                if ((row + column) % 2 == 0) drawRect(colors.surfaceSunken, Offset(x, y), Size(cell, cell))
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
}

/** Decodes the photo at most 2048 px on the long side, upright. */
private fun decodePhoto(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > 2048) {
                val scale = 2048f / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}.getOrNull()
