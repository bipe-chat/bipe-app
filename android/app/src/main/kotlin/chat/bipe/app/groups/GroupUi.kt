// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bar on top of the group screens: back (or close), the title and actions on the right. */
@Composable
fun GroupTopBar(
    title: String,
    onBack: (() -> Unit)?,
    backIcon: PhosphorIcon = PhosphorIcon.CARET_LEFT,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(backIcon.drawable), contentDescription = null, tint = colors.glyphPrimary)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** A row with an icon, a title and an optional value on the right. */
@Composable
fun GroupRow(icon: PhosphorIcon, title: String, value: String? = null, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = tint ?: colors.glyphPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint ?: colors.inkPrimary, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
    }
}

/** A section title above a list. */
@Composable
fun GroupSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = BipeTheme.colors.inkSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** A footer line under a section. */
@Composable
fun GroupFooter(text: String, color: androidx.compose.ui.graphics.Color = BipeTheme.colors.inkSecondary) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Reads a picked photo. The photo goes up as JPEG or as the type the picker gives. */
suspend fun readPhoto(context: Context, uri: Uri): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    runCatching {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        mime to bytes
    }.getOrNull()
}
