// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.stickers.StickerImportCandidate
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

internal suspend fun readStickerFiles(paths: List<Path>): List<StickerImportCandidate> = withContext(Dispatchers.IO) {
    val candidates = linkedMapOf<String, StickerImportCandidate>()
    var retainedBytes = 0
    fun readSticker(stream: InputStream) {
        val bytes = stream.readNBytes(BipeRegistries.STICKER_IMPORT_MAX_BYTES + 1)
        val candidate = StickerImportCandidate.create(bytes) {
            val image = runCatching { SkiaImage.makeFromEncoded(it) }.getOrNull() ?: return@create null
            try { image.width to image.height } finally { image.close() }
        } ?: return
        if (candidate.shortcode in candidates) return
        retainedBytes += bytes.size
        check(retainedBytes <= 32 * 1024 * 1024)
        candidates[candidate.shortcode] = candidate
    }
    for (path in paths) {
        ensureActive()
        val filename = path.fileName.toString()
        when (filename.substringAfterLast('.', "").lowercase()) {
            "webp", "png" -> Files.newInputStream(path).use(::readSticker)
            "zip" -> {
                check(Files.size(path) <= 256L * 1024 * 1024)
                ZipFile(path.toFile()).use { zip ->
                    for (entry in zip.entries()) {
                        ensureActive()
                        if (!entry.isDirectory && entry.name.endsWith(".webp", ignoreCase = true) &&
                            entry.size in 1..BipeRegistries.STICKER_IMPORT_MAX_BYTES.toLong()) {
                            zip.getInputStream(entry).use(::readSticker)
                        }
                    }
                }
            }
        }
    }
    candidates.values.toList()
}
