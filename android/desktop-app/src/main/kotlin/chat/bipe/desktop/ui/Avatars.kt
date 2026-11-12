// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.chat.ChatRow
import chat.bipe.desktop.chat.AccountIdentity
import chat.bipe.desktop.chat.AccountType
import chat.bipe.shared.BipeOfficialAccounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image as SkiaImage

/** Shared DM/room identity: peer photo for DMs, room photo then up to three heroes for groups. */
@Composable internal fun Avatar(row: ChatRow, media: AccountMedia, identity: AccountIdentity, colors: DesktopColors, size: Dp = 48.dp) {
    if (!row.isDm && row.avatar == null && row.members.isNotEmpty()) {
        val members = row.members.take(3)
        if (members.size == 1) AvatarPhoto(members[0].name, members[0].url, media, identity, colors, size, userId = members[0].userId, cluster = true)
        else Box(Modifier.size(size).clip(CircleShape).background(colors["accent.wash"])) {
            members.forEachIndexed { index, member ->
                val step = size * 0.375f
                val position = if (members.size == 2) listOf(0.dp to 0.dp, step to step)
                    else listOf(0.dp to 0.dp, step to 0.dp, step / 2 to step)
                val (x, y) = position[index]
                Box(Modifier.offset(x, y).border(1.dp, colors["surface.base"], CircleShape)) {
                    AvatarPhoto(member.name, member.url, media, identity, colors, size * 0.625f, userId = member.userId, cluster = true)
                }
            }
        }
    } else AvatarPhoto(row.name, row.avatar, media, identity, colors, size, userId = row.peerUserId.takeIf { row.isDm })
}

@Composable internal fun AvatarPhoto(name: String, url: String?, media: AccountMedia, identity: AccountIdentity,
                                    colors: DesktopColors, size: Dp = 48.dp, userId: String? = null,
                                    isCompany: Boolean = false, cluster: Boolean = false) {
    if (BipeOfficialAccounts.isOfficial(userId)) { OfficialAvatar(colors, size, cluster); return }
    val company by key(identity, userId, isCompany, cluster) { produceState(isCompany && !cluster) {
        if (!cluster && !isCompany && userId != null) try { value = identity.type(userId) == AccountType.COMPANY }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Unknown or retired identities stay round. */ }
    } }
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
    Box(Modifier.size(size).clip(if (company) RoundedCornerShape(percent = 25) else CircleShape)
        .background(colors["accent.wash"]), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(Regex("\\X").find(name)?.value.orEmpty(), color = colors["accent.ink"], style = MaterialTheme.typography.labelLarge)
    }
}

/** Official identities never load a mutable profile photo. The path and colours are the shared Bipe artwork. */
@Composable private fun OfficialAvatar(colors: DesktopColors, diameter: Dp, cluster: Boolean) {
    val geometry = colors.catalog.markGeometry
    val path = remember(geometry) { PathParser().parsePathString(geometry.getValue("path").jsonPrimitive.content).toPath() }
    val side = geometry.getValue("viewBox").jsonPrimitive.float
    Box(Modifier.size(diameter).clip(if (cluster) CircleShape else RoundedCornerShape(percent = 25))
        .background(colors["accent.fill"]), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter * 0.58f)) {
            scale(size.width / side, size.height / side, pivot = Offset.Zero) { drawPath(path, colors["ink.onAccent"]) }
        }
    }
}
