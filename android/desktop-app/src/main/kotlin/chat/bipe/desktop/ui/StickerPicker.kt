// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.chat.StickerActions
import chat.bipe.stickers.Sticker
import chat.bipe.stickers.StickerPack
import chat.bipe.stickers.StickerSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface StickerPhase {
    data object Loading : StickerPhase
    data object LoadFailed : StickerPhase
    data class Ready(val picker: StickerActions.Picker, val phase: Action = Action.IDLE) : StickerPhase
    data class Import(val phase: StickerImportPhase) : StickerPhase
    enum class Action { IDLE, WORKING, SEND_FAILED, CHANGE_FAILED }
}

private sealed interface StickerImportPhase {
    data object Intro : StickerImportPhase
    data object Importing : StickerImportPhase
    data class Done(val added: Int) : StickerImportPhase
    data object Failed : StickerImportPhase
}

@Composable internal fun StickerPicker(actions: StickerActions, media: AccountMedia, s: DesktopCatalog, close: () -> Unit) {
    var phase by remember(actions) { mutableStateOf<StickerPhase>(StickerPhase.Loading) }
    var attempt by remember(actions) { mutableStateOf(0) }
    var query by remember(actions) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(actions, attempt) {
        phase = StickerPhase.Loading
        phase = try { StickerPhase.Ready(actions.picker()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { StickerPhase.LoadFailed }
    }
    val importing = (phase as? StickerPhase.Import)?.phase
    val busy = (phase as? StickerPhase.Ready)?.phase == StickerPhase.Action.WORKING || importing == StickerImportPhase.Importing
    fun leaveImport() {
        phase = StickerPhase.Loading
        attempt++
    }
    fun importFiles() {
        val current = phase as? StickerPhase.Import ?: return
        if (current.phase == StickerImportPhase.Importing) return
        val paths = pickFiles(s.text("bipe_stickers_import_action"))
        if (paths.isEmpty()) return
        phase = StickerPhase.Import(StickerImportPhase.Importing)
        scope.launch {
            phase = StickerPhase.Import(try { StickerImportPhase.Done(actions.importStickers(readStickerFiles(paths))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { StickerImportPhase.Failed })
        }
    }
    fun act(sticker: Sticker, send: Boolean) {
        val current = phase as? StickerPhase.Ready ?: return
        if (current.phase == StickerPhase.Action.WORKING) return
        phase = current.copy(phase = StickerPhase.Action.WORKING)
        scope.launch {
            try {
                if (send) { actions.send(current.picker, sticker); close() }
                else { actions.toggleFavourite(sticker); phase = StickerPhase.Ready(actions.picker()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                phase = current.copy(phase = if (send) StickerPhase.Action.SEND_FAILED else StickerPhase.Action.CHANGE_FAILED)
            }
        }
    }
    val dismiss = { if (!busy) { if (importing == null) close() else leaveImport() } }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(s.text(if (importing == null) "bipe_stickers_title" else "bipe_stickers_import_action")) },
        text = {
            when (val current = phase) {
                StickerPhase.Loading -> CircularProgressIndicator()
                StickerPhase.LoadFailed -> Column {
                    Text(s.text("bipe_stickers_error_load"))
                    TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                }
                is StickerPhase.Import -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val result = current.phase) {
                        StickerImportPhase.Intro -> Text(s.text("bipe_stickers_import_help"))
                        StickerImportPhase.Importing -> {
                            Text(s.text("bipe_stickers_import_progress"))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        is StickerImportPhase.Done -> Text(if (result.added == 0) s.text("bipe_stickers_import_none")
                            else s.text("bipe_stickers_import_done", result.added.toString()))
                        StickerImportPhase.Failed -> Text(s.text("bipe_stickers_import_failed"))
                    }
                    if (!busy) Text(s.text("bipe_stickers_import_note"))
                }
                is StickerPhase.Ready -> Column {
                    OutlinedTextField(query, { query = it }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(s.text("bipe_stickers_search_placeholder")) })
                    val picker = current.picker
                    fun title(pack: StickerPack): String = when (pack.source) {
                        StickerPack.Source.User -> s.text("bipe_stickers_pack_personal")
                        is StickerPack.Source.Room -> pack.displayName ?: s.text("bipe_stickers_pack_room")
                    }
                    val favourites = picker.favourites.mapTo(mutableSetOf()) { it.id }
                    val sections = if (query.isBlank()) {
                        listOf(s.text("bipe_stickers_favourites") to picker.favourites, s.text("bipe_stickers_recents") to picker.recents) +
                            picker.packs.map { title(it) to it.stickers }
                    } else {
                        val titles = picker.packs.associate { it.id to title(it) }
                        val found = StickerSearch.results(query, listOf(picker.favourites, picker.recents) + picker.packs.map { it.stickers }) { titles[it] }
                        listOf(null to found)
                    }.filter { it.second.isNotEmpty() }
                    if (sections.isEmpty()) {
                        Text(s.text(if (query.isBlank()) "bipe_stickers_empty_title" else "bipe_stickers_search_empty"),
                            Modifier.padding(vertical = 8.dp))
                    } else LazyColumn(Modifier.heightIn(max = 350.dp)) {
                        sections.forEachIndexed { index, (heading, stickers) ->
                            heading?.let { label -> item(key = "section-$index") {
                                Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                            } }
                            items(stickers, key = { "$index/${it.id}" }) { sticker ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    StickerThumbnail(sticker, media)
                                    TextButton(onClick = { act(sticker, true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                        Text(s.text("bipe_a11y_sticker_send", sticker.body))
                                    }
                                    val label = s.text(if (sticker.id in favourites) "bipe_sticker_favourite_remove" else "bipe_sticker_favourite_add")
                                    IconButton(onClick = { act(sticker, false) }, enabled = !busy) {
                                        Icon(sharedIcon(if (sticker.id in favourites) "check" else "star"), contentDescription = label)
                                    }
                                }
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (current.phase == StickerPhase.Action.SEND_FAILED) Text(s.text("bipe_app_sticker_send_failed"))
                    if (current.phase == StickerPhase.Action.CHANGE_FAILED) Text(s.text("bipe_error_generic"))
                }
            }
        },
        dismissButton = {
            if (phase is StickerPhase.Ready) TextButton(onClick = { phase = StickerPhase.Import(StickerImportPhase.Intro) }, enabled = !busy) {
                Text(s.text("bipe_stickers_import_action"))
            }
            if (importing != null && !busy) TextButton(onClick = ::importFiles) {
                Text(s.text("bipe_stickers_import_action"))
            }
        },
        confirmButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(s.text("bipe_app_close")) } })
}

@Composable private fun StickerThumbnail(sticker: Sticker, media: AccountMedia) {
    val image by key(media, sticker.url) { produceState<ImageBitmap?>(null) {
        try {
            val bytes = media.originalAvatar(sticker.url) ?: return@produceState
            value = withContext(Dispatchers.Default) { decodePicture(bytes, 96) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the send action with its label. */ }
    } }
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp)) }
    }
}
