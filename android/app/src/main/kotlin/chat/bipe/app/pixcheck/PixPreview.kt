// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.content.Context
import android.content.Intent
import chat.bipe.app.MainActivity
import java.io.File
import java.util.UUID

/**
 * The editable send preview of spec pix-check-result (action sendToChat): the original shared text, or the
 * original image. It waits in the app for chat choice and Send; it never sends by itself.
 */
data class PixPreview(val id: String, val text: String?, val image: File?, val createdAtMillis: Long) {
    val isExpired: Boolean get() = System.currentTimeMillis() - createdAtMillis >= LIFETIME_MS

    /** Used once: the image copy goes when the preview is sent, cancelled or expired. */
    fun delete() {
        image?.delete()
    }

    companion object {
        const val ACTION = "chat.bipe.app.action.PIX_PREVIEW"
        private const val EXTRA_ID = "chat.bipe.app.extra.PIX_PREVIEW_ID"
        private const val EXTRA_TEXT = "chat.bipe.app.extra.PIX_PREVIEW_TEXT"
        private const val EXTRA_IMAGE = "chat.bipe.app.extra.PIX_PREVIEW_IMAGE"
        private const val EXTRA_CREATED = "chat.bipe.app.extra.PIX_PREVIEW_CREATED"
        private const val LIFETIME_MS = 5 * 60 * 1000L
        private const val FOLDER = "pix-pending"
        private const val PREFS = "bipe_pix_check"
        private const val KEY_ACCOUNT = "account_available"

        /** accountAvailable of spec pix-check-result. A hint only: the app still asks for chat and Send. */
        fun accountAvailable(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCOUNT, false)

        fun setAccountAvailable(context: Context, available: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCOUNT, available).apply()
        }

        /** Saves the image of the preview where only [from] accepts it, then opens the app with it. */
        fun open(context: Context, text: String?, image: File?) {
            val id = UUID.randomUUID().toString()
            val copy = image?.let { source ->
                val folder = File(context.filesDir, FOLDER).apply { mkdirs() }
                File(folder, "$id.img").also { source.copyTo(it, overwrite = true) }
            }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXT, if (copy == null) text else null)
                .putExtra(EXTRA_IMAGE, copy?.name)
                .putExtra(EXTRA_CREATED, System.currentTimeMillis())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }

        /**
         * The preview of an intent, consumed once. The image is only a file name here: [resolve] accepts it
         * inside the app's own pix-pending folder only, so another app cannot point it at a private file.
         */
        fun from(intent: Intent?): PixPreview? {
            if (intent?.action != ACTION) return null
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val preview = PixPreview(
                id = id,
                text = intent.getStringExtra(EXTRA_TEXT),
                image = intent.getStringExtra(EXTRA_IMAGE)?.let { File(it).name }?.let(::File),
                createdAtMillis = intent.getLongExtra(EXTRA_CREATED, 0L),
            )
            intent.action = null
            return preview
        }

        /** Resolves the image name against the pix-pending folder; anything else is refused. */
        fun resolve(context: Context, preview: PixPreview): PixPreview? {
            if (preview.isExpired) return null
            val name = preview.image?.name ?: return preview.takeIf { !it.text.isNullOrBlank() }
            val folder = File(context.filesDir, FOLDER).canonicalFile
            val file = File(folder, name).canonicalFile
            if (file.parentFile != folder || !file.exists()) return null
            return preview.copy(image = file)
        }

        /** Deletes copies older than the lifetime, left by a cancelled or killed flow. */
        fun deleteStale(context: Context) {
            File(context.filesDir, FOLDER).listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() >= LIFETIME_MS) file.delete()
            }
        }
    }
}
