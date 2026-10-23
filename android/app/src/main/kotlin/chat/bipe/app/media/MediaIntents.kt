// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The decrypted file leaves the app only when the person opens, shares or saves it (spec media-viewer notes). */
object MediaIntents {
    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.media", file)

    /** Action open of a file row: the system app for the type, through the chooser. */
    fun open(context: Context, file: File, mimeType: String?) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Action share: the system share sheet. */
    fun share(context: Context, file: File, mimeType: String?) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Action save: copies the photo or video into the phone gallery. */
    suspend fun saveToGallery(context: Context, file: File, filename: String, mimeType: String?, isVideo: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val type = mimeType ?: if (isVideo) "video/mp4" else "image/jpeg"
                val collection = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Bipe")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val folder = Environment.getExternalStoragePublicDirectory(
                            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                        )
                        val dir = File(folder, "Bipe").apply { mkdirs() }
                        @Suppress("DEPRECATION")
                        put(MediaStore.MediaColumns.DATA, File(dir, filename).path)
                    }
                }
                val resolver = context.contentResolver
                val target = resolver.insert(collection, values) ?: return@runCatching false
                resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: return@runCatching false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                true
            }.getOrDefault(false)
        }
}
