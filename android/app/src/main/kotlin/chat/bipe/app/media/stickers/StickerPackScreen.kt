// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.stickers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.BipeHandleResolver
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.stickers.StickerPackPreview
import chat.bipe.stickers.StickerPackRef
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership

/** phase of spec sticker-pack. */
private enum class PackPhase { LOADING, LOADED, ADDED, FAILED }

/** Spec sticker-pack: a pack from a pack link or from a received sticker, and adding it (ADR 0205, bipe-chat/bipe#842). */
@Composable
fun StickerPackScreen(client: Client, ref: StickerPackRef, loader: MediaLoader, handles: BipeHandleResolver?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val packClient = remember(client) { stickerPackClient(client) }
    var phase by remember(ref) { mutableStateOf(PackPhase.LOADING) }
    var preview by remember(ref) { mutableStateOf<StickerPackPreview?>(null) }
    var authorHandle by remember(ref) { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    // Action appear.
    LaunchedEffect(ref, attempt) {
        phase = PackPhase.LOADING
        val loaded = packClient?.let { runCatching { it.preview(ref) }.getOrNull() }
        if (loaded == null) {
            phase = PackPhase.FAILED
            return@LaunchedEffect
        }
        preview = loaded
        phase = if (runCatching { packClient?.isEnabled(ref) }.getOrNull() == true) PackPhase.ADDED else PackPhase.LOADED
        authorHandle = loaded.creatorId?.let { handles?.resolve(it) }
    }

    // Action add: joins the public room, then adds it to im.ponies.emote_rooms.
    fun add() {
        val packs = packClient ?: return
        if (adding) return
        adding = true
        scope.launch {
            val ok = runCatching {
                if (runCatching { client.getRoom(ref.roomId)?.membership() }.getOrNull() != Membership.JOINED) client.joinRoomById(ref.roomId)
                packs.enable(ref)
            }.isSuccess
            adding = false
            phase = if (ok) PackPhase.ADDED else PackPhase.FAILED
        }
    }

    val title = preview?.pack?.displayName ?: preview?.roomName ?: stringResource(R.string.bipe_stickers_title)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.surfaceBase).safeDrawingPadding().testTag(BipeScreen.STICKER_PACK.id)) {
            Header(title, stringResource(R.string.bipe_app_close), onClose)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (phase) {
                    PackPhase.LOADING -> CircularProgressIndicator(color = colors.accentFill)
                    PackPhase.FAILED -> Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.bipe_sticker_pack_failed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry), color = colors.accentInk) }
                    }
                    PackPhase.LOADED, PackPhase.ADDED -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(76.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        authorHandle?.let { handle ->
                            item(key = "author", span = { GridItemSpan(maxLineSpan) }) {
                                Text(stringResource(R.string.bipe_sticker_pack_by, "@$handle"), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                            }
                        }
                        items(preview?.pack?.stickers.orEmpty(), key = { it.id }) { sticker ->
                            PackCell(sticker.url, sticker.body, loader)
                        }
                    }
                }
            }
            if (phase == PackPhase.LOADED || phase == PackPhase.ADDED) {
                Box(modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(16.dp), contentAlignment = Alignment.Center) {
                    if (phase == PackPhase.ADDED) {
                        Text(stringResource(R.string.bipe_sticker_pack_added), fontWeight = FontWeight.SemiBold, color = colors.inkSecondary)
                    } else {
                        BipeButton(onClick = ::add, enabled = !adding, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_sticker_pack_add))
                        }
                    }
                }
            }
        }
    }
}

/** One sticker of the pack: the server's still (sticker-pack shows the pack; the picker plays it). */
@Composable
private fun PackCell(url: String, body: String, loader: MediaLoader) {
    val colors = BipeTheme.colors
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { image = loader.thumbnail(url, 228) }
    Box(
        modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).semantics { contentDescription = body },
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
