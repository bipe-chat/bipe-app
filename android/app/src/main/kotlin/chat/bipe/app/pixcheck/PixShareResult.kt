// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.PixCheck
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeLightColors
import java.io.File
import java.io.FileOutputStream

/**
 * Action shareResult of spec pix-check-result (bipe#746, phase 1): the text and card that go back to the chat.
 * The text states only what the code says, never that the Pix is safe. It is a check result, never a payment action.
 */
object PixShareResult {
    private const val DIR = "pixcheck-share"
    private const val CARD_WIDTH = 1080
    private const val PADDING = 60

    /** The text for one report, or null when the report has nothing to send back. */
    fun text(context: Context, item: PixCheckedItem): String? {
        item.code?.let { code ->
            when (code.status) {
                PixCheck.Status.BAD_CRC, PixCheck.Status.MALFORMED -> return context.getString(R.string.bipe_pix_check_share_altered)
                PixCheck.Status.VALID -> {
                    val name = code.name?.trim()
                    if (!name.isNullOrEmpty()) {
                        return code.amount?.let { context.getString(R.string.bipe_pix_check_share_code, name, formatAmount(it)) }
                            ?: context.getString(R.string.bipe_pix_check_share_code_open_amount, name)
                    }
                }
            }
        }
        return item.keys.firstOrNull()?.let { context.getString(R.string.bipe_pix_check_share_key, it.value) }
    }

    /** Opens the share sheet with the text and the card; the person picks the app and the chat. */
    fun share(context: Context, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { card(context, text) }.getOrNull()?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
            send.setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
        }
        return runCatching { context.startActivity(Intent.createChooser(send, null)) }.isSuccess
    }

    /** Close deletes the card with the other local results. */
    fun clear(context: Context) {
        File(context.cacheDir, DIR).deleteRecursively()
    }

    /** The same text as plain text, no link or QR, on the light theme. */
    private fun card(context: Context, text: String): File {
        val colors = BipeLightColors
        val nunito = ResourcesCompat.getFont(context, chat.bipe.app.R.font.nunito) ?: Typeface.DEFAULT
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(nunito, Typeface.BOLD)
            textSize = 44f
            color = colors.accentInk.toArgb()
        }
        val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(nunito, Typeface.BOLD)
            textSize = 56f
            color = colors.inkPrimary.toArgb()
        }
        val inner = CARD_WIDTH - 2 * PADDING
        val iconSize = 52
        val title = context.getString(R.string.bipe_pix_check_title)
        val body = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        val headerHeight = iconSize
        val gap = 36
        val height = PADDING + headerHeight + gap + body.height + PADDING
        val image = Bitmap.createBitmap(CARD_WIDTH, height, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(colors.surfaceRaised.toArgb())
            ContextCompat.getDrawable(context, PhosphorIcon.MAGNIFYING_GLASS.drawable)?.mutate()?.let { icon ->
                icon.setTint(colors.accentInk.toArgb())
                icon.setBounds(PADDING, PADDING, PADDING + iconSize, PADDING + iconSize)
                icon.draw(this)
            }
            val baseline = PADDING + headerHeight / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
            drawText(title, PADDING + iconSize + 20f, baseline, titlePaint)
            save()
            translate(PADDING.toFloat(), (PADDING + headerHeight + gap).toFloat())
            body.draw(this)
            restore()
        }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "bipe-conferir-pix.png").also { out ->
            FileOutputStream(out).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
