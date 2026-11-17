// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder

/** Module geometry for a Compose Canvas: square integer scaling, no interpolation, four-module quiet zone. */
class LinkQrModules internal constructor(private val modules: Array<BooleanArray>) {
    val side: Int get() = modules.size
    fun isDark(x: Int, y: Int): Boolean = modules[y][x]
}

object BinaryLinkQr {
    fun encode(payload: LinkQrPayload): LinkQrModules {
        val bytes = payload.copyBytes()
        // ZXing 3.5.3 defaults to ISO-8859-1 and one mode for the whole input. Do not supply CHARACTER_SET:
        // even an explicit ISO-8859-1 hint adds an ECI header. Do not enable QR_COMPACT segment splitting.
        val code = try {
            Encoder.encode(bytes.toString(Charsets.ISO_8859_1), ErrorCorrectionLevel.L)
        } finally { bytes.fill(0) }
        // Do not ship an optimization that splits MATRIX into another segment: released phones rejected that.
        if (code.mode != Mode.BYTE) throw PhoneLinkException(PhoneLinkFailure.PROTOCOL)
        val matrix = code.matrix ?: throw PhoneLinkException(PhoneLinkFailure.PROTOCOL)
        val quiet = 4
        return LinkQrModules(Array(matrix.height + quiet * 2) { y ->
            BooleanArray(matrix.width + quiet * 2) { x ->
                x in quiet until matrix.width + quiet && y in quiet until matrix.height + quiet &&
                    matrix.get(x - quiet, y - quiet).toInt() == 1
            }
        })
    }
}
