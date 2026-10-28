// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Conferir no Bipe (specs pix-check-receive and pix-check-result): text and images shared from any app.
 * Its own activity, so it needs no account and starts no session, sign-in or network request.
 */
class PixCheckActivity : ComponentActivity() {
    private val scope = MainScope()
    private val model = PixCheckReceiveModel(scope)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A share is read once; a recreation keeps the result through the model's state only.
        if (savedInstanceState == null) readShare(intent)
        setContent {
            BipeTheme {
                val state by model.state.collectAsState()
                PixCheckScreen(
                    state = state,
                    onCancel = {
                        model.cancel()
                        finish()
                    },
                    onClose = {
                        model.close()
                        PixShareResult.clear(this)
                        finish()
                    },
                    onRetry = model::retry,
                    accountAvailable = PixPreview.accountAvailable(this),
                    // Only after the tap: the app shows the editable preview and asks for chat choice and Send.
                    onSendToChat = { item ->
                        val image = model.sourceImage(item)
                        runCatching { PixPreview.open(this, text = item.input, image = image) }
                            .onSuccess {
                                model.close()
                                finish()
                            }
                            // handoffFailed: a generic error; a new tap retries the same preview.
                            .onFailure { Toast.makeText(this, getString(R.string.bipe_error_generic), Toast.LENGTH_SHORT).show() }
                    },
                )
            }
        }
    }

    /** A new share while open replaces the input; the older check can no longer publish. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            model.close()
            PixShareResult.clear(this)
        }
        scope.cancel()
        super.onDestroy()
    }

    /** Reads EXTRA_TEXT and the granted streams, copying images to a private file while the grant holds. */
    private fun readShare(intent: Intent?) {
        val share = intent ?: return
        if (share.action != Intent.ACTION_SEND && share.action != Intent.ACTION_SEND_MULTIPLE) return
        // Consumed once: recomposition or a return to the app does not repeat it.
        setIntent(Intent())
        scope.launch {
            val items = withContext(Dispatchers.IO) { items(share) }
            model.receive(items)
        }
    }

    private fun items(intent: Intent): List<PixSharedItem> {
        val items = mutableListOf<PixSharedItem>()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { items += PixSharedItem.Text(it) }
        streams(intent).take(PixCheckReceiveModel.MAX_ITEMS + 1).forEach { uri ->
            val type = contentResolver.getType(uri).orEmpty()
            when {
                type.startsWith("image/") -> copyImage(uri)?.let { items += PixSharedItem.Image(it) }
                type == "text/plain" -> readText(uri)?.let { items += PixSharedItem.Text(it) }
            }
        }
        return items
    }

    private fun streams(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            uris += if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
        } else {
            val single: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            single?.let { uris += it }
        }
        if (uris.isEmpty()) uris += clipUris(intent.clipData)
        return uris.distinct()
    }

    private fun clipUris(clip: ClipData?): List<Uri> =
        (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }

    /** Stops at the 10 MiB limit without reading the rest; an over-limit image is not kept. */
    private fun copyImage(uri: Uri): File? = runCatching {
        val file = File.createTempFile("pix-", ".img", cacheDir)
        val complete = contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > PixCheckReceiveModel.MAX_IMAGE_BYTES) return@use false
                    output.write(buffer, 0, read)
                }
                true
            }
        } ?: false
        if (complete) file else null.also { file.delete() }
    }.getOrNull()

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            // At most the text limit in UTF-8 (4 bytes a code point); the check rejects longer text.
            val bytes = input.readNBytesCompat(PixCheckReceiveModel.MAX_TEXT_CODE_POINTS * 4 + 1)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
