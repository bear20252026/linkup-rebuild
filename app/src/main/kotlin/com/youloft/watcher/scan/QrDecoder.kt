package com.youloft.watcher.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * The decode half of the scan screen.
 *
 * The original delegated this to `com.king.zxing:zxing-lite` (`ob.a.x(bitmap)` for a still image, the
 * library's own analyzer for the preview). We keep that library out of the build — its shipped version
 * cannot be recovered from the APK, and the layout already talks CameraX directly — so this is the same
 * job done on `com.google.zxing:core` plus CameraX, with one configuration detail preserved: the
 * recovered screen set `decodeFormats = QR_CODE_FILTER_DATA`, i.e. **QR only**, and
 * `cropRatio = 1.0f`, i.e. the whole frame is analysed rather than the library's 0.8 default.
 */
object QrDecoder {

    private val reader = MultiFormatReader()

    /** A still image, e.g. a picture picked from the gallery. */
    fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
    }

    /** One CameraX analysis frame. The Y plane *is* the luminance buffer, minus the row padding. */
    fun decode(image: ImageProxy): String? {
        val buffer: ByteBuffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return decode(
            PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false),
        )
    }

    private fun decode(source: LuminanceSource): String? = runCatching {
        reader.decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    }.getOrNull().also { reader.reset() }

    /**
     * `QRCodeScanActivity.O(options, 800, 800)` verbatim: the recovered down-sampling rule for a
     * gallery picture, halving until the half-dimension drops below the requested size.
     */
    internal fun sampleSizeFor(outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (outHeight <= reqHeight && outWidth <= reqWidth) return 1
        var sample = 1
        val halfHeight = outHeight / 2
        val halfWidth = outWidth / 2
        while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
            sample *= 2
        }
        return sample
    }
}
