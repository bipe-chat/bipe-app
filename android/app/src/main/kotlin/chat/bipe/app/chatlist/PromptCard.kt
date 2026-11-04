// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.bipeTapTarget

/** One button of a [PromptCard]. */
data class PromptAction(val title: String, val run: () -> Unit)

/**
 * Spec chat-list-prompt (bipe-chat/bipe#894): the one card shape of every chat list prompt. Title, body, an optional
 * extra line, one filled button and text buttons (Agora não last, or the close label of the remote banner).
 */
@Composable
fun PromptCard(
    title: String,
    body: String?,
    primary: PromptAction?,
    texts: List<PromptAction>,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).then(modifier)
            .background(colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        listOfNotNull(body, note).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        if (primary != null || texts.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                primary?.let {
                    Text(
                        it.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.inkOnAccent,
                        modifier = Modifier.bipeTapTarget().background(colors.accentFill, RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClick = it.run)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                texts.forEach {
                    Text(
                        it.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.accentInk,
                        modifier = Modifier.bipeTapTarget().clickable(role = Role.Button, onClick = it.run),
                    )
                }
            }
        }
    }
}
