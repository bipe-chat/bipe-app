// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import chat.bipe.desktop.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun OriginalPhoto(title: String, s: DesktopCatalog, close: () -> Unit, load: suspend () -> ByteArray?) {
    DialogWindow(onCloseRequest = close, title = title, state = rememberDialogState(width = 900.dp, height = 700.dp)) {
        Surface(Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    OriginalPhotoContent(title, s, load)
                }
                TextButton(onClick = close) { Text(s.text("bipe_app_close")) }
            }
        }
    }
}

@Composable private fun OriginalPhotoContent(title: String, s: DesktopCatalog, load: suspend () -> ByteArray?) {
    var attempt by remember { mutableStateOf(0) }
    val state by produceState<LoadPhase<ImageBitmap>>(LoadPhase.Loading, attempt) {
        value = LoadPhase.Loading
        value = try {
            val bytes = load()
            val image = bytes?.let { withContext(Dispatchers.Default) { decodePicture(it, maxSide = 3072) } }
            image?.let { LoadPhase.Ready(it) } ?: LoadPhase.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LoadPhase.Failed }
    }
    when (val result = state) {
        LoadPhase.Loading -> CircularProgressIndicator()
        LoadPhase.Failed -> TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_media_failed")) }
        is LoadPhase.Ready -> Image(result.value, contentDescription = title,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable internal fun ChatGallery(timeline: DesktopTimeline, loader: AccountMedia, policy: MediaShowPolicy,
                                    s: DesktopCatalog, close: () -> Unit) {
    var attempt by remember(timeline) { mutableStateOf(0) }
    val phase by produceState<LoadPhase<DayHistory>>(LoadPhase.Loading, timeline, attempt) {
        value = LoadPhase.Loading
        val history = try { timeline.openGallery() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = LoadPhase.Failed; return@produceState }
        try {
            value = LoadPhase.Ready(history)
            awaitCancellation()
        } finally { history.close() }
    }
    DialogWindow(onCloseRequest = close, title = s.text("bipe_gallery_title"),
        state = rememberDialogState(width = 840.dp, height = 660.dp)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val current = phase) {
                        LoadPhase.Loading -> CircularProgressIndicator()
                        LoadPhase.Failed -> TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                        is LoadPhase.Ready -> GalleryContent(current.value, loader, policy, s) { attempt++ }
                    }
                }
                TextButton(onClick = close) { Text(s.text("bipe_app_close")) }
            }
        }
    }
}

@Composable private fun GalleryContent(history: DayHistory, loader: AccountMedia, policy: MediaShowPolicy,
                                      s: DesktopCatalog, retry: () -> Unit) {
    val phase by history.rows.collectAsState()
    val pages by history.pages.collectAsState()
    var files by remember(history) { mutableStateOf(false) }
    var selected by remember(history) { mutableStateOf<String?>(null) }
    val messages = (phase as? LoadPhase.Ready)?.value.orEmpty().asReversed()
        .filter { row -> row.media?.kind in setOf(MessageMedia.Kind.IMAGE, MessageMedia.Kind.VIDEO, MessageMedia.Kind.FILE) }
    val rows = messages.filter { (it.media?.kind == MessageMedia.Kind.FILE) == files }
    LaunchedEffect(history, files, phase, pages) {
        if (phase is LoadPhase.Ready && rows.size < 30 && pages.earlier && pages.status == DayHistory.Paging.IDLE)
            history.paginate(earlier = true)
    }
    val index = rows.indexOfFirst { it.id == selected }
    val active = rows.getOrNull(index)?.media
    val scope = rememberCoroutineScope()
    var saveFailed by remember(history, selected) { mutableStateOf(false) }
    fun save(media: MessageMedia) {
        val destination = chooseDestination(media.filename, s.text("bipe_app_save")) ?: return
        scope.launch {
            try { loader.save(media, destination); saveFailed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saveFailed = true }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (active != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = null }) { Text(s.text("bipe_a11y_back")) }
                IconButton(onClick = { selected = rows[index - 1].id }, enabled = index > 0) {
                    Icon(sharedIcon("caret-left"), contentDescription = s.text("bipe_gallery_media") + " " + index)
                }
                Text("${index + 1} / ${rows.size}")
                IconButton(onClick = { selected = rows[index + 1].id }, enabled = index + 1 < rows.size) {
                    Icon(sharedIcon("caret-right"), contentDescription = s.text("bipe_gallery_media") + " " + (index + 2))
                }
                TextButton(onClick = { save(active) }) { Text(s.text("bipe_app_save")) }
            }
            if (saveFailed) Text(s.text("bipe_app_save_error"))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                key(active.sourceJson) {
                    if (active.kind == MessageMedia.Kind.IMAGE) OriginalPhotoContent(active.filename, s) { loader.eventPicture(active.sourceJson) }
                    else Text(active.filename)
                }
            }
            return
        }
        TabRow(selectedTabIndex = if (files) 1 else 0) {
            Tab(selected = !files, onClick = { files = false }, text = { Text(s.text("bipe_gallery_media")) })
            Tab(selected = files, onClick = { files = true }, text = { Text(s.text("bipe_gallery_files")) })
        }
        when {
            phase == LoadPhase.Failed -> TextButton(onClick = retry) { Text(s.text("bipe_app_retry")) }
            rows.isEmpty() && (phase == LoadPhase.Loading || pages.status == DayHistory.Paging.LOADING) -> CircularProgressIndicator()
            rows.isEmpty() && !pages.earlier -> Text(s.text(if (files) "bipe_gallery_empty_files" else "bipe_gallery_empty_media"))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files) items(rows, key = { it.id }) { row ->
                val media = row.media ?: return@items
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(media.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        mediaDetails(media, s.locale)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    TextButton(onClick = { save(media) }) { Text(s.text("bipe_app_save")) }
                }
            } else items(rows.chunked(3), key = { it.first().id }) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { row ->
                        val media = checkNotNull(row.media)
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            GalleryTile(media, policy.shows(row.own), loader, s) { selected = row.id }
                        }
                    }
                    repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (pages.earlier) item {
                TextButton(onClick = { history.paginate(earlier = true) }, enabled = pages.status != DayHistory.Paging.LOADING) {
                    Text(s.text(if (pages.status == DayHistory.Paging.FAILED) "bipe_app_retry" else "bipe_action_show_more"))
                }
            }
        }
        if (saveFailed) Text(s.text("bipe_app_save_error"))
    }
}

@Composable private fun GalleryTile(media: MessageMedia, shows: Boolean, loader: AccountMedia, s: DesktopCatalog, open: () -> Unit) {
    val picture by key(loader, media.previewJson, shows) { produceState<ImageBitmap?>(null) {
        if (!shows) return@produceState
        try {
            val bytes = media.previewJson?.let { loader.eventPicture(it) }
            value = bytes?.let { withContext(Dispatchers.Default) { decodePicture(it, 384) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the labelled tile available to retry from its viewer. */ }
    } }
    val label = s.text(s.mediaKey(media.kind))
    Surface(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = label, onClick = open),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val preview = picture
            if (preview == null) Text(label)
            else Image(preview, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            media.durationMillis?.let { duration ->
                Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                    Text(durationLabel(duration), Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
