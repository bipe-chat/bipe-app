// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.chat.ChatRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** Shared DM/room identity: peer photo for DMs, room photo then up to three heroes for groups. */
@Composable internal fun Avatar(row: ChatRow, media: AccountMedia, colors: DesktopColors, size: Dp = 48.dp) {
    if (!row.isDm && row.avatar == null && row.members.isNotEmpty()) {
        val members = row.members.take(3)
        if (members.size == 1) AvatarPhoto(members[0].name, members[0].url, media, colors, size)
        else Box(Modifier.size(size).clip(CircleShape).background(colors["accent.wash"])) {
            members.forEachIndexed { index, member ->
                val step = size * 0.375f
                val position = if (members.size == 2) listOf(0.dp to 0.dp, step to step)
                    else listOf(0.dp to 0.dp, step to 0.dp, step / 2 to step)
                val (x, y) = position[index]
                Box(Modifier.offset(x, y).border(1.dp, colors["surface.base"], CircleShape)) {
                    AvatarPhoto(member.name, member.url, media, colors, size * 0.625f)
                }
            }
        }
    } else AvatarPhoto(row.name, row.avatar, media, colors, size)
}

@Composable internal fun AvatarPhoto(name: String, url: String?, media: AccountMedia, colors: DesktopColors, size: Dp = 48.dp) {
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    // Re-key the state itself, so even the first frame cannot show another account's cached image.
    val image by key(media, url, pixels) { produceState<ImageBitmap?>(null) {
        if (url == null) return@produceState
        try {
            val bytes = media.thumbnail(url, pixels) ?: return@produceState
            value = withContext(Dispatchers.Default) {
                val decoded = SkiaImage.makeFromEncoded(bytes)
                if (decoded.width !in 1..1024 || decoded.height !in 1..1024) { decoded.close(); null }
                else decoded.toComposeImageBitmap()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Missing, malformed and unavailable images keep the name fallback. */ }
    } }
    Box(Modifier.size(size).clip(CircleShape).background(colors["accent.wash"]), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(Regex("\\X").find(name)?.value.orEmpty(), color = colors["accent.ink"], style = MaterialTheme.typography.labelLarge)
    }
}
