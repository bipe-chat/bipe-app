// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import chat.bipe.app.media.upload.copyToCache
import java.io.File
import java.util.UUID

/**
 * A share of spec share-to-chat, read from ACTION_SEND or ACTION_SEND_MULTIPLE. The files are copies in the app
 * cache (the provider's grant ends with the activity). It waits for the chat choice and Send; it never sends by itself.
 */
data class PendingShare(
    val id: String,
    val text: String,
    val files: List<Pair<File, String?>>,
    val droppedOverLimit: Boolean,
    /** The chat of a sharing shortcut, already picked. */
    val shortcut: ShareShortcuts.Target?,
    val createdAtMillis: Long,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - createdAtMillis >= LIFETIME_MS

    /** Action cancel: deletes every copy. */
    fun delete() {
        files.forEach { (file, _) -> file.parentFile?.deleteRecursively() }
    }

    companion object {
        const val MAX_ITEMS = 10
        private const val LIFETIME_MS = 15 * 60 * 1000L
    }
}

object ShareIntake {
    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    /**
     * The URIs and the text of a share, read once: the intent action is cleared, so a later recomposition or return
     * to the app does not repeat it. The copies are made by [read].
     */
    fun take(intent: Intent): Shared? {
        if (!isShare(intent)) return null
        val found = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { found.addAll(it) }
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { found.add(it) }
        }
        val clip = intent.clipData
        if (found.isEmpty() && clip != null) {
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { found.add(it) }
        }
        val uris = found.distinct()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        intent.action = null
        return Shared(uris, text, shortcutId?.let(ShareShortcuts::target))
    }

    class Shared(val uris: List<Uri>, val text: String, val shortcut: ShareShortcuts.Target?)

    /** Copies the first 10 items while the grant exists. A link or text is never fetched. */
    suspend fun read(context: Context, shared: Shared): PendingShare? {
        val textCount = if (shared.text.isNotEmpty()) 1 else 0
        val room = PendingShare.MAX_ITEMS - textCount
        val files = shared.uris.take(room).mapNotNull { copyToCache(context, it) }
        if (files.isEmpty() && shared.text.isEmpty()) return null
        return PendingShare(
            id = UUID.randomUUID().toString(),
            text = shared.text,
            files = files,
            droppedOverLimit = shared.uris.size > room,
            shortcut = shared.shortcut,
            createdAtMillis = System.currentTimeMillis(),
        )
    }
}
