// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import chat.bipe.app.common.BipeToasts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square JPEG for profile photos: at most `profilePhotoMaxPixels` on each side and `profilePhotoMaxBytes`. */
object BipeProfilePhoto {
    /** Crops the centre square, scales it down and re-encodes it as JPEG with no metadata. `null` when it does not fit. */
    fun jpeg(bitmap: Bitmap, maxPixels: Int = BipeRegistries.PROFILE_PHOTO_MAX_PIXELS, maxBytes: Int = BipeRegistries.PROFILE_PHOTO_MAX_BYTES): ByteArray? {
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) return null
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val target = minOf(maxPixels, side)
        val scaled = if (target == side) square else Bitmap.createScaledBitmap(square, target, target, true)
        for (quality in listOf(85, 75, 60, 45, 30)) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
        }
        return null
    }

    /** Reads a picked image. */
    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
}

/** Spec profile-link: the user's link (bipe.chat/@handle) and its QR code. */
@Composable
fun ProfileLinkScreen(people: PeopleService, pickHandle: () -> Unit, onBack: () -> Unit, showQr: Boolean = false) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<String?>(null) }
    var showingQr by remember { mutableStateOf(showQr) }
    LaunchedEffect(Unit) {
        handle = runCatching { people.withOpenId { people.api.myHandle(it) } }.getOrNull()?.handle
        loaded = true
    }
    PeopleScaffold(
        stringResource(R.string.bipe_app_profile_link),
        BipeScreen.PROFILE_LINK.id,
        onBack,
    ) {
        val link = handle?.let(BipeProfileLink::url)
        when {
            link != null -> ProfileLinkContent(
                link = link,
                showingQr = showingQr,
                onShare = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                },
                onCopy = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(link, link))
                    BipeToasts.copied(context)
                },
                onToggleQr = { showingQr = !showingQr },
            )
            loaded -> {
                Text(stringResource(R.string.bipe_public_profile_needs_handle), color = colors.inkSecondary)
                TextButton(onClick = pickHandle) { Text(stringResource(R.string.bipe_handle_prompt_action)) }
            }
            else -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
        }
    }
}

/** The link, its actions and the QR code of spec profile-link; the store captures call it too (bipe-chat/bipe#457). */
@Composable
fun ColumnScope.ProfileLinkContent(link: String, showingQr: Boolean, onShare: () -> Unit, onCopy: () -> Unit, onToggleQr: () -> Unit) {
    val colors = BipeTheme.colors
    Text(link, fontFamily = FontFamily.Monospace, color = colors.inkPrimary)
    RowGroup {
        IconRow(PhosphorIcon.SHARE_NETWORK, stringResource(R.string.bipe_profile_share), onClick = onShare)
        IconRow(PhosphorIcon.COPY, stringResource(R.string.bipe_app_copy), onClick = onCopy)
        IconRow(PhosphorIcon.QR_CODE, stringResource(R.string.bipe_profile_qr_action), onClick = onToggleQr)
    }
    if (showingQr) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.bipe_profile_qr_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            QrCode(link)
            Text(stringResource(R.string.bipe_profile_qr_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}

/** A QR code that holds exactly [text], dark on light in both themes, drawn on the device (ZXing). */
@Composable
fun QrCode(text: String) {
    val image = remember(text) { qrBitmap(text)?.asImageBitmap() } ?: return
    // Scanners need dark modules on a light ground in both themes (spec profile-link); no token is fixed light.
    Box(Modifier.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Image(BitmapPainter(image, filterQuality = androidx.compose.ui.graphics.FilterQuality.None), contentDescription = text, modifier = Modifier.size(240.dp))
    }
}

/** The QR code of [text], one pixel per module and no margin; also the counter QR code of queue-owner. */
internal fun qrBitmap(text: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
