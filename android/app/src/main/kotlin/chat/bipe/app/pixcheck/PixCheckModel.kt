// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import chat.bipe.shared.PixCheck
import chat.bipe.shared.ScamWarningKind
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeMultiReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/** Type PixSharedItem of spec pix-check-receive. The image is a private temporary copy, never a remote URL. */
sealed interface PixSharedItem {
    data class Text(val text: String) : PixSharedItem
    data class Image(val file: File) : PixSharedItem
}

/** Type PixCheckedItem of spec pix-check-result: one checked text or QR payload. */
data class PixCheckedItem(
    /** The provider item index; an image can give several reports. */
    val sourceIndex: Int,
    /** The checked text or QR payload, kept on the phone for the send preview only. */
    val input: String,
    val check: PixCheck,
) {
    val code: PixCheck.Code? get() = check.code
    val keys: List<PixCheck.Key> get() = check.keys

    /** The rule warning, never pixCode or pixKey (PixCheck skips them). */
    val warningKind: ScamWarningKind? get() = check.warning
}

/** Spec pix-check-receive: reads shared text or QR images and runs the Pix check on the phone. No network. */
class PixCheckReceiveModel(private val scope: CoroutineScope) {
    enum class Phase { MISSING_INPUT, READING, CHECKING, READY, UNSUPPORTED, NO_QR, NOTHING_FOUND, FAILED, CANCELLED }

    data class State(
        val phase: Phase = Phase.READING,
        val results: List<PixCheckedItem> = emptyList(),
        val canRetry: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var items: List<PixSharedItem> = emptyList()

    /** Every operation has a generation: a newer share or a cancel makes older completions inert. */
    private var generation = 0
    private var work: Job? = null

    /** Action receive. */
    fun receive(newItems: List<PixSharedItem>) {
        generation++
        work?.cancel()
        deleteImages(except = newItems)
        items = newItems
        if (newItems.isEmpty()) {
            _state.value = State(Phase.MISSING_INPUT)
            return
        }
        run(generation)
    }

    /** Action retry: repeats the local read and check from the kept input. */
    fun retry() {
        if (!_state.value.canRetry) return
        generation++
        run(generation)
    }

    /** Action cancel: stops the work and deletes the temporary files. No later result shows. */
    fun cancel() {
        generation++
        work?.cancel()
        deleteImages(except = emptyList())
        items = emptyList()
        _state.value = State(Phase.CANCELLED)
    }

    /** Action close: deletes the kept input and results. */
    fun close() = cancel()

    /** The shared image a report came from, for the send preview; null for text. */
    fun sourceImage(item: PixCheckedItem): java.io.File? =
        (items.getOrNull(item.sourceIndex) as? PixSharedItem.Image)?.file

    private fun run(runGeneration: Int) {
        val current = items
        _state.value = State(if (current.any { it is PixSharedItem.Image }) Phase.READING else Phase.CHECKING)
        work = scope.launch {
            val outcome = withContext(Dispatchers.Default) { check(current) }
            if (runGeneration != generation) return@launch
            _state.value = outcome
        }
    }

    private fun deleteImages(except: List<PixSharedItem>) {
        items.filterIsInstance<PixSharedItem.Image>().filter { it !in except }.forEach { it.file.delete() }
    }

    companion object {
        /** Limits of the spec notes. */
        const val MAX_ITEMS = 10
        const val MAX_TEXT_CODE_POINTS = 100_000
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val MAX_IMAGE_PIXELS = 4_000_000

        /**
         * Same order on both platforms: provider order; per image, distinct QR payloads sorted lexically.
         * Each payload is checked on its own, never joined into one BR Code.
         */
        fun check(items: List<PixSharedItem>): State {
            val results = mutableListOf<PixCheckedItem>()
            var checkedInputs = 0
            var imagesWithoutQr = 0
            var rejected = maxOf(0, items.size - MAX_ITEMS)
            var failed = false
            var retryable = false
            items.take(MAX_ITEMS).forEachIndexed { index, item ->
                when (item) {
                    is PixSharedItem.Text -> {
                        if (item.text.codePointCount(0, item.text.length) > MAX_TEXT_CODE_POINTS) {
                            rejected++
                        } else {
                            checkedInputs++
                            results += PixCheckedItem(index, item.text, PixCheck.check(item.text))
                        }
                    }
                    is PixSharedItem.Image -> when (val read = qrPayloads(item.file)) {
                        ImageRead.TooLarge -> rejected++
                        ImageRead.Unreadable -> {
                            failed = true
                            retryable = item.file.exists()
                        }
                        is ImageRead.Payloads -> {
                            if (read.values.isEmpty()) imagesWithoutQr++
                            read.values.forEach { payload ->
                                checkedInputs++
                                results += PixCheckedItem(index, payload, PixCheck.check(payload))
                            }
                        }
                    }
                }
            }
            val phase = when {
                results.any { it.check.foundPix } -> Phase.READY
                failed && checkedInputs == 0 -> Phase.FAILED
                // A rule warning stays in the results even when no Pix was found.
                checkedInputs > 0 -> Phase.NOTHING_FOUND
                imagesWithoutQr > 0 -> Phase.NO_QR
                rejected > 0 -> Phase.UNSUPPORTED
                else -> Phase.MISSING_INPUT
            }
            return State(phase, results, canRetry = phase == Phase.FAILED && retryable)
        }

        private sealed interface ImageRead {
            data class Payloads(val values: List<String>) : ImageRead
            data object TooLarge : ImageRead
            data object Unreadable : ImageRead
        }

        /** Decodes on the phone: size checked first, the image shrunk to at most 4 megapixels. */
        private fun qrPayloads(file: File): ImageRead {
            if (!file.exists()) return ImageRead.Unreadable
            if (file.length() > MAX_IMAGE_BYTES) return ImageRead.TooLarge
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImageRead.Unreadable
            var sample = 1
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > MAX_IMAGE_PIXELS) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return ImageRead.Unreadable
            val scaled = shrink(bitmap)
            return try {
                ImageRead.Payloads(decode(scaled).distinct().sorted())
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }

        private fun shrink(bitmap: Bitmap): Bitmap {
            val pixels = bitmap.width.toLong() * bitmap.height
            if (pixels <= MAX_IMAGE_PIXELS) return bitmap
            val scale = sqrt(MAX_IMAGE_PIXELS.toDouble() / pixels)
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }

        private fun decode(bitmap: Bitmap): List<String> {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
            return try {
                QRCodeMultiReader().decodeMultiple(binary, hints).mapNotNull { it.text }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
