// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class BinaryLinkQrTest {
    @Test fun matrixPrefixAndEveryByteDecodeAsOneUnchangedBinarySegment() {
        // Independent renderer fixture, not a valid server login or a real key. Includes the original Mac failure's
        // alphanumeric prefix plus zeros/high bytes, to reject prefix splitting and UTF-8/ECI conversion.
        val original = "MATRIX".toByteArray(Charsets.US_ASCII) + ByteArray(256) { it.toByte() }
        val modules = BinaryLinkQr.encode(LinkQrPayload(original))
        val scale = 8
        val side = modules.side * scale
        val pixels = IntArray(side * side) { index ->
            if (modules.isDark((index % side) / scale, (index / side) / scale)) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = RGBLuminanceSource(side, side, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<*>
        assertEquals(1, segments.size)
        assertArrayEquals(original, segments.single() as ByteArray)
        // A byte-mode QR without ECI has symbology identifier ]Q1; ECI would be ]Q2.
        assertEquals("]Q1", result.resultMetadata[ResultMetadataType.SYMBOLOGY_IDENTIFIER])
    }

    @Test fun quietZoneIsFourClearModulesOnEveryEdge() {
        val modules = BinaryLinkQr.encode(LinkQrPayload(byteArrayOf(0, 1, -128, -1)))
        for (y in 0 until modules.side) for (x in 0 until modules.side) {
            if (x < 4 || y < 4 || x >= modules.side - 4 || y >= modules.side - 4) assertFalse(modules.isDark(x, y))
        }
    }
}
