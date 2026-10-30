// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/**
 * Spec chat-timeline notes, assistant readers (ADR 0130, bipe-chat/bipe#439): one persistent notice at the top of the
 * timeline per member whose assistant reads the chat. The room state is read again each time the app comes to the
 * foreground.
 */
@Composable
internal fun AssistantReaderNotices(model: ChatTimelineModel) {
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshAssistantReaders() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val readers by model.assistantReaders.collectAsState()
    for (reader in readers) {
        key(reader.userId) { AssistantReaderNotice(reader.name) }
    }
}

/** The notice for one member. It has no action and no close. */
@Composable
internal fun AssistantReaderNotice(name: String) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.INFO.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.bipe_app_assistant_reads, name), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}
