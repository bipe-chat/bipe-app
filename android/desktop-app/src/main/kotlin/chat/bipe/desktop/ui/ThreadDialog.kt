// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.chat.LoadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

@Composable internal fun ThreadDialog(parent: DesktopTimeline, eventId: String, s: DesktopCatalog, close: () -> Unit,
                                       content: @Composable (DesktopTimeline) -> Unit) {
    var attempt by remember(parent, eventId) { mutableStateOf(0) }
    val phase by key(parent, eventId, attempt) { produceState<LoadPhase<DesktopTimeline>>(LoadPhase.Loading) {
        val thread = try { parent.openThread(eventId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = LoadPhase.Failed; return@produceState }
        try {
            value = LoadPhase.Ready(thread)
            awaitCancellation()
        } finally { thread.close() }
    } }
    DialogWindow(onCloseRequest = close, title = s.text("bipe_thread_title"),
        state = rememberDialogState(width = 760.dp, height = 670.dp)) {
        Surface(Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text(s.text("bipe_app_close")) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val current = phase) {
                        LoadPhase.Loading -> CircularProgressIndicator()
                        LoadPhase.Failed -> TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                        is LoadPhase.Ready -> key(current.value) { content(current.value) }
                    }
                }
            }
        }
    }
}
