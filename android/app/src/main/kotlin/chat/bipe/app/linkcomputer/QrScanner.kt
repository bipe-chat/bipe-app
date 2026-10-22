// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.linkcomputer

import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Camera preview that reads QR codes and hands over their raw bytes. The MSC4108 code is binary, so the bytes come
 * from the byte segments of the decoded code, not from its text.
 */
@Composable
fun QrScanner(onCode: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentOnCode = rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(previewView) {
        val activity = context as ComponentActivity
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var lastCode: ByteArray? = null
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = QRCodeReader()
            analysis.setAnalyzer(executor) { image ->
                val bytes = decode(reader, image)
                image.close()
                if (bytes != null && !bytes.contentEquals(lastCode)) {
                    lastCode = bytes
                    ContextCompat.getMainExecutor(context).execute { currentOnCode.value(bytes) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** The bytes of a QR code in the luminance plane of a YUV frame, or null. */
private fun decode(reader: QRCodeReader, image: ImageProxy): ByteArray? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val data = ByteArray(width * height)
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(data, row * width, width)
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    val result = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints) }.getOrNull()
    reader.reset()
    @Suppress("UNCHECKED_CAST")
    val segments = result?.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray> ?: return null
    return segments.fold(ByteArray(0)) { all, segment -> all + segment }.takeIf { it.isNotEmpty() }
}
