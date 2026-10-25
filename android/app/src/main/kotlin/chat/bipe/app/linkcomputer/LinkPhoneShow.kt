// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.linkcomputer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Debug builds and the emulator only: the copy button of link-phone-show and the paste field of recovery-old-phone. */
fun canCopyQrCode(context: Context): Boolean {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.HARDWARE == "goldfish" || Build.HARDWARE == "ranchu" || Build.PRODUCT.contains("sdk")
    return debuggable || emulator
}

/** Spec link-phone-show: the old phone shows the code that the new phone scans (ADR 0059), then takes the check code. */
@Composable
internal fun LinkPhoneShowContent(state: LinkComputerModel.State, model: LinkComputerModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    // The code must stay on screen while the new phone scans it.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(BipeScreen.LINK_PHONE_SHOW.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.support) {
            LinkComputerModel.Support.CHECKING -> CircularProgressIndicator(color = colors.accentFill)
            LinkComputerModel.Support.NEEDS_PIN -> Body(stringResource(R.string.bipe_app_link_phone_needs_pin, BipeRegistries.PIN_LENGTH.toString()))
            LinkComputerModel.Support.NOT_SUPPORTED -> Body(stringResource(R.string.bipe_app_link_phone_not_supported))
            LinkComputerModel.Support.SUPPORTED -> ShowPhases(state, model)
        }
    }
}

@Composable
private fun ShowPhases(state: LinkComputerModel.State, model: LinkComputerModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    when (state.showPhase) {
        LinkComputerModel.ShowPhase.PREPARING -> {
            Title(stringResource(R.string.bipe_app_link_phone_title))
            CircularProgressIndicator(color = colors.accentFill)
            Text(stringResource(R.string.bipe_app_link_phone_preparing), color = colors.inkSecondary)
        }
        LinkComputerModel.ShowPhase.SHOWING_CODE -> {
            Title(stringResource(R.string.bipe_app_link_phone_title))
            Text(stringResource(R.string.bipe_app_link_phone_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            val code = state.qrCode
            val bitmap = remember(code) { code?.let(::renderQr) }
            if (code != null && bitmap != null) {
                // A white field with a quiet zone, in light and dark mode.
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
                )
                if (canCopyQrCode(context)) {
                    var copied by remember(code) { mutableStateOf(false) }
                    BipeOutlinedButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Bipe", code))
                        copied = true
                    }) {
                        Text(stringResource(if (copied) R.string.bipe_app_link_phone_copied else R.string.bipe_app_link_phone_copy))
                    }
                }
            }
            Text(stringResource(R.string.bipe_app_link_phone_never_share), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
        }
        LinkComputerModel.ShowPhase.ENTER_CHECK_CODE, LinkComputerModel.ShowPhase.CHECKING -> {
            Title(stringResource(R.string.bipe_app_link_phone_check_code_title))
            Text(stringResource(R.string.bipe_app_link_phone_check_code_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = state.typedCheckCode,
                onValueChange = model::editCheckCode,
                enabled = state.showPhase == LinkComputerModel.ShowPhase.ENTER_CHECK_CODE,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, color = colors.inkPrimary),
                modifier = Modifier.widthIn(max = 160.dp).focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            if (state.showPhase == LinkComputerModel.ShowPhase.CHECKING) CircularProgressIndicator(color = colors.accentFill)
        }
        LinkComputerModel.ShowPhase.FAILED -> {
            Text(stringResource(R.string.bipe_app_link_phone_error_title), style = MaterialTheme.typography.titleMedium, color = colors.trustDangerInk)
            Text(
                stringResource((state.showError ?: LinkComputerModel.Failure.GENERIC).message(model.mode)),
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            BipeButton(onClick = model::newCode) { Text(stringResource(R.string.bipe_app_link_phone_new_code)) }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

/**
 * The MSC4108 code is binary: ISO-8859-1 maps each byte to one character, and ZXing then writes one byte-mode segment
 * with no ECI, so the scanner gets the bytes back as they are.
 */
private fun renderQr(base64: String): Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val content = String(bytes, Charsets.ISO_8859_1)
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) Color.BLACK else Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
