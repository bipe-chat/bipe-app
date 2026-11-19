// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.AttachmentActions
import chat.bipe.desktop.chat.AttachmentInput
import chat.bipe.shared.generated.BipeRegistries
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun pickAttachments(title: String): List<AttachmentInput> {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    return try {
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files.take(AttachmentActions.MAX_ITEMS).map { AttachmentInput.File(it.toPath().toAbsolutePath()) }
    } finally { dialog.dispose() }
}

internal fun pastedAttachments(): List<AttachmentInput> = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.attachments().orEmpty()
}.getOrDefault(emptyList())

private fun Transferable.attachments(): List<AttachmentInput> = when {
    isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
        (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
            .take(AttachmentActions.MAX_ITEMS).map { AttachmentInput.File(it.toPath().toAbsolutePath()) }
    isDataFlavorSupported(DataFlavor.imageFlavor) ->
        (getTransferData(DataFlavor.imageFlavor) as? Image)?.let { listOf(AttachmentInput.Clipboard(it)) }.orEmpty()
    else -> emptyList()
}

@Composable internal fun AttachmentDropTarget(enabled: Boolean, receive: (List<AttachmentInput>) -> Unit) {
    val window = LocalDesktopWindow.current ?: return
    val current by rememberUpdatedState(receive)
    DisposableEffect(window, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val previous = window.dropTarget
        val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
                    !event.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    event.rejectDrop()
                    return
                }
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val items = runCatching { event.transferable.attachments() }.getOrDefault(emptyList())
                event.dropComplete(items.isNotEmpty())
                if (items.isNotEmpty()) current(items)
            }
        })
        onDispose { if (window.dropTarget === target) window.dropTarget = previous }
    }
}

private sealed interface PreviewPhase {
    data object Opening : PreviewPhase
    data object Failed : PreviewPhase
    data class Editing(val batch: AttachmentActions.Batch, val drafts: List<AttachmentActions.Draft>,
                       val caption: String, val reply: String?) : PreviewPhase
    data class Sending(val editing: Editing) : PreviewPhase
}

@Composable internal fun AttachmentPreview(actions: AttachmentActions, inputs: List<AttachmentInput>, reply: String?,
                                          s: DesktopCatalog, close: () -> Unit) {
    var phase by remember(actions, inputs) { mutableStateOf<PreviewPhase>(PreviewPhase.Opening) }
    var attempt by remember(actions, inputs) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(actions, inputs, attempt) {
        phase = PreviewPhase.Opening
        phase = try {
            val batch = actions.inspect(inputs)
            PreviewPhase.Editing(batch, batch.items.map { AttachmentActions.Draft(it) }, "", reply)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PreviewPhase.Failed }
    }
    val busy = phase is PreviewPhase.Sending
    val edit = phase as? PreviewPhase.Editing
    val frame = edit ?: (phase as? PreviewPhase.Sending)?.editing
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(s.text("bipe_app_composer_attach")) },
        text = {
            when {
                phase == PreviewPhase.Opening -> CircularProgressIndicator()
                phase == PreviewPhase.Failed -> TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                frame != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(frame.drafts, key = { _, draft -> draft.item.input }) { index, draft ->
                            val item = draft.item
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.filename)
                                    val locale = Locale.forLanguageTag(s.locale)
                                    if (item.bytes > 0) Text(sizeLabel(item.bytes, locale), style = MaterialTheme.typography.labelSmall)
                                    if (item.bytes > frame.batch.limit) Text(s.text("bipe_app_upload_too_large", sizeLabel(frame.batch.limit, locale)))
                                    if (draft.failed) Text(s.text("bipe_app_upload_failed"))
                                }
                                TextButton(enabled = !busy, onClick = {
                                    val remaining = frame.drafts.filterIndexed { i, _ -> i != index }
                                    if (remaining.isEmpty()) close() else phase = frame.copy(drafts = remaining)
                                }) { Text(s.text("bipe_app_message_action_delete")) }
                            }
                            if (item.kind == AttachmentActions.Kind.IMAGE) {
                                val image by produceState<ImageBitmap?>(null, actions, item) {
                                    try { value = decodePicture(actions.preview(item), maxSide = 500) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { /* The send attempt reports preparation failures. */ }
                                }
                                image?.let { bitmap ->
                                    Image(bitmap, contentDescription = item.filename, contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
                                }
                                OutlinedTextField(draft.description, { value ->
                                    val changed = draft.copy(description = value.take(BipeRegistries.IMAGE_DESCRIPTION_MAX_LENGTH))
                                    phase = frame.copy(drafts = frame.drafts.mapIndexed { i, v -> if (index == i) changed else v })
                                }, enabled = !busy, label = { Text(s.text("bipe_app_upload_description")) },
                                    supportingText = { Text(s.text("bipe_app_upload_description_footer")) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    OutlinedTextField(frame.caption, { phase = frame.copy(caption = it) }, enabled = !busy,
                        label = { Text(s.text("bipe_app_upload_caption_placeholder")) }, modifier = Modifier.fillMaxWidth())
                    if (busy) Text(s.text("bipe_app_upload_preparing"))
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(s.text("bipe_app_cancel")) } },
        confirmButton = {
            TextButton(enabled = edit != null && edit.drafts.none { it.item.bytes > edit.batch.limit }, onClick = {
                val current = phase as? PreviewPhase.Editing ?: return@TextButton
                phase = PreviewPhase.Sending(current)
                scope.launch {
                    val result = try { actions.send(current.batch, current.drafts, current.caption, current.reply) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        phase = current.copy(drafts = current.drafts.map { it.copy(failed = true) })
                        return@launch
                    }
                    if (result.remaining.isEmpty()) close()
                    else phase = current.copy(drafts = result.remaining, caption = result.caption, reply = result.reply)
                }
            }) { Text(s.text("bipe_app_upload_send")) }
        },
    )
}
