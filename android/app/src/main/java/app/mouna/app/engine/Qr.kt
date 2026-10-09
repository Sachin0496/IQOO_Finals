package app.mouna.app.engine

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The join link as a QR code, for the other person to scan from any phone camera. */
object Qr {
    /** The code's modules, no quiet zone: a square matrix, `true` = dark. */
    fun matrix(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))

    /** Dark modules in [dark] on [light], [scale] pixels per module and a four-module quiet zone (the QR standard). */
    fun bitmap(text: String, scale: Int = 8, dark: Int = 0xFF0E0D0B.toInt(), light: Int = 0xFFEDE6D6.toInt()): Bitmap {
        val m = matrix(text)
        val quiet = 4
        val side = (m.width + quiet * 2) * scale
        val px = IntArray(side * side) { light }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (!m[x, y]) continue
            for (dy in 0 until scale) {
                val row = ((y + quiet) * scale + dy) * side + (x + quiet) * scale
                java.util.Arrays.fill(px, row, row + scale, dark)
            }
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    }
}
