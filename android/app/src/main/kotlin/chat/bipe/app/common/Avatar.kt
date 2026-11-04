// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.Person
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.avatarAccountIsCompany
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.RoomInfo
import java.util.concurrent.ConcurrentHashMap

/** Loads avatar thumbnails through the SDK media API and keeps them in memory. */
class MediaLoader(val client: Client) {
    private val cache = LruCache<String, ImageBitmap>(100)
    private val accountTypes = ConcurrentHashMap<String, Boolean>()
    private val api = BipeApiClient(AppConfig.API_BASE_URL)

    suspend fun avatarUrl(userId: String): String? = runCatching { client.getProfile(userId).avatarUrl }.getOrNull()

    /** Unknown accounts stay round. Account type is independent of the verification badge (ADR 0132). */
    suspend fun isCompany(userId: String): Boolean {
        accountTypes[userId]?.let { return it }
        val company = runCatching { api.avatarAccountIsCompany(userId) }.getOrNull() ?: return false
        accountTypes[userId] = company
        return company
    }

    suspend fun thumbnail(mxcUrl: String, size: Int): ImageBitmap? {
        val key = "$mxcUrl#$size"
        cache.get(key)?.let { return it }
        val bytes = runCatching {
            client.getMediaThumbnail(MediaSource.fromUrl(mxcUrl), size.toULong(), size.toULong())
        }.getOrNull() ?: return null
        val image = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: return null
        cache.put(key, image)
        return image
    }
}

/**
 * A person's circle or a company's rounded square, with the actual photo or initials (ADR 0132).
 * loader null: initials only, with no account (the store captures, bipe-chat/bipe#457).
 * members: spec chat-list notes, Group avatar (bipe-chat/bipe#1004): a group with no photo shows these members.
 */
@Composable
fun Avatar(
    name: String,
    mxcUrl: String?,
    loader: MediaLoader?,
    size: Dp = 48.dp,
    userId: String? = null,
    isCompany: Boolean = false,
    showsPhoto: Boolean = true,
    members: List<Person> = emptyList(),
) {
    if (mxcUrl == null && members.isNotEmpty()) {
        MemberCluster(members, loader, size, showsPhoto)
        return
    }
    val colors = BipeTheme.colors
    var image by remember(userId, mxcUrl, showsPhoto) { mutableStateOf<ImageBitmap?>(null) }
    var company by remember(userId, isCompany) { mutableStateOf(isCompany) }
    LaunchedEffect(loader, userId, mxcUrl, size, isCompany, showsPhoto) {
        if (loader == null) return@LaunchedEffect
        launch {
            val url = if (showsPhoto) mxcUrl ?: userId?.let { loader.avatarUrl(it) } else null
            image = url?.let { loader.thumbnail(it, (size.value * 3).toInt()) }
        }
        if (!isCompany && userId != null) launch { company = loader.isCompany(userId) }
    }
    Box(
        modifier = Modifier.size(size).clip(if (company || isCompany) RoundedCornerShape(percent = 25) else CircleShape).background(colors.accentWash),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                initial(name),
                color = colors.accentInk,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Diameter and centre of a member circle, as fractions of the group circle. */
private data class ClusterSlot(val diameter: Float, val x: Float, val y: Float)

/** The layouts for 2 and 3 members. */
private val clusterLayouts = listOf(
    listOf(ClusterSlot(0.56f, 0.36f, 0.38f), ClusterSlot(0.48f, 0.66f, 0.64f)),
    listOf(ClusterSlot(0.5f, 0.36f, 0.38f), ClusterSlot(0.36f, 0.72f, 0.36f), ClusterSlot(0.4f, 0.6f, 0.73f)),
)

/** Spec chat-list notes, Group avatar: up to 3 members as overlapping circles inside the group circle. */
@Composable
private fun MemberCluster(members: List<Person>, loader: MediaLoader?, size: Dp, showsPhoto: Boolean) {
    if (members.size == 1) {
        val person = members.first()
        Avatar(person.displayName, person.avatarUrl, loader, size, userId = person.userId, showsPhoto = showsPhoto)
        return
    }
    val colors = BipeTheme.colors
    val slots = clusterLayouts[minOf(members.size, 3) - 2]
    Box(modifier = Modifier.size(size).clip(CircleShape).background(colors.surfaceSunken)) {
        slots.forEachIndexed { index, slot ->
            val person = members[index]
            val diameter = size * slot.diameter
            // No userId: the hero already carries the photo, and the circles stay round.
            Box(
                modifier = Modifier
                    .offset(x = size * slot.x - diameter / 2, y = size * slot.y - diameter / 2)
                    .size(diameter)
                    .border(maxOf(1.dp, size * 0.025f), colors.surfaceSunken, CircleShape),
            ) {
                Avatar(person.displayName, person.avatarUrl, loader, size = diameter, showsPhoto = showsPhoto)
            }
        }
    }
}

/**
 * Spec chat-list notes, Group avatar (bipe-chat/bipe#1004): the heroes of a group with no m.room.avatar,
 * without the own user, at most 3. Empty for a DM or a group with a photo.
 */
fun groupAvatarMembers(info: RoomInfo, ownUserId: String): List<Person> {
    if (info.isDm || !info.avatarUrl.isNullOrEmpty()) return emptyList()
    return info.heroes.filter { it.userId != ownUserId }.take(3)
        .map { Person(it.userId, Person.name(it.userId, it.displayName), it.avatarUrl) }
}

private fun initial(name: String): String =
    name.trimStart('@', '#', '!', '+', ' ').firstOrNull()?.uppercase() ?: ""
