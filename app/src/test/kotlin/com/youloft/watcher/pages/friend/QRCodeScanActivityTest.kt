package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.R
import com.youloft.watcher.scan.QrDecoder
import com.youloft.watcher.view.MatchCodeQrPainter
import com.youloft.watcher.view.rasterize
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The scan screen's contract is small and specific — what it hands back, from which permission it got
 * the gallery picture, and how big a bitmap it is willing to decode — so that is what is pinned here.
 * The camera itself is behind [QRCodeScanActivity.CameraBinder] and stays unbound in tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QRCodeScanActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shareUrl = "https://web-link.tttick.com/business/guard-report#/addMe?userid=1234567890&av=1.6.6"

    private fun launch(): QRCodeScanActivity {
        val controller = Robolectric.buildActivity(QRCodeScanActivity::class.java)
        controller.get().cameraBinder = QRCodeScanActivity.CameraBinder { _, _, _ -> }
        controller.setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    /**
     * Built from the painter's plan through `setPixels`, not from `render()`: this machine's Robolectric
     * does not carry Canvas drawing into the pixel buffer, so a Canvas-painted bitmap would test nothing.
     */
    private fun bitmapOf(content: String, sizePx: Int): Bitmap {
        val plan = assertNotNull(MatchCodeQrPainter().plan(content, sizePx))
        val frame = rasterize(plan, quietModules = 4)
        return Bitmap.createBitmap(frame.pixels, frame.size, frame.size, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun `the decoder reads back what the painter lays out`() {
        assertEquals(shareUrl, QrDecoder.decode(bitmapOf(shareUrl, 288)))
    }

    @Test
    fun `a bitmap with no code in it decodes to nothing`() {
        val blank = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(200 * 200) { android.graphics.Color.WHITE }
        blank.setPixels(pixels, 0, 200, 0, 0, 200, 200)

        assertNull(QrDecoder.decode(blank))
    }

    @Test
    fun `picking a code from the gallery returns it through the recovered extra`() {
        val activity = launch()

        activity.onGalleryResult(
            ActivityResult(
                android.app.Activity.RESULT_OK,
                Intent().setData(android.net.Uri.parse("content://media/pick")),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        // No real image behind that uri, so nothing is delivered and the screen stays open.
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `a gallery result with no uri is ignored`() {
        val activity = launch()

        activity.onGalleryResult(ActivityResult(android.app.Activity.RESULT_OK, Intent()))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `the close button leaves without a result`() {
        val activity = launch()

        activity.window.decorView.findViewById<android.widget.ImageView>(R.id.iv_close).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).resultIntent?.getStringExtra(QRCodeScanActivity.EXTRA_SCAN_RESULT))
    }

    @Test
    fun `delivering a scan writes the recovered extra and finishes`() {
        val activity = launch()

        activity.deliverResult(shareUrl)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(
            shareUrl,
            shadowOf(activity).resultIntent?.getStringExtra(QRCodeScanActivity.EXTRA_SCAN_RESULT),
            "the key the add-friend screen reads is SCAN_RESULT, as in both shipped builds",
        )
    }

    @Test
    fun `a second result cannot overwrite the first`() {
        val activity = launch()

        activity.deliverResult("first")
        activity.deliverResult("second")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("first", shadowOf(activity).resultIntent?.getStringExtra(QRCodeScanActivity.EXTRA_SCAN_RESULT))
    }

    @Test
    fun `the down-sampling rule is the recovered half-dimension loop`() {
        // 4000x3000 against 800x800: halves until a half-dimension drops under the target.
        // The recovered loop compares *half* dimensions, so 3000 high only earns a 2.
        assertEquals(2, QrDecoder.sampleSizeFor(outWidth = 4000, outHeight = 3000, reqWidth = 800, reqHeight = 800))
        assertEquals(1, QrDecoder.sampleSizeFor(800, 800, 800, 800))
        assertEquals(1, QrDecoder.sampleSizeFor(100, 100, 800, 800))
        assertEquals(8, QrDecoder.sampleSizeFor(6400, 6400, 800, 800))
    }

    @Test
    fun `the mask dim and the scan line duration are the recovered constants`() {
        assertEquals(153, QRCodeScanActivity.MASK_ALPHA, "Color.argb(153, 0, 0, 0) in the original")
        assertEquals(2000L, QRCodeScanActivity.SCAN_LINE_DURATION_MILLIS)
        assertEquals(800, QRCodeScanActivity.GALLERY_SIZE)
        assertEquals("image/*", QRCodeScanActivity.IMAGE_MIME_TYPE)
        assertEquals("SCAN_RESULT", QRCodeScanActivity.EXTRA_SCAN_RESULT)
    }

    @Test
    fun `the intent the add-friend screen launches is this screen`() {
        val started = QRCodeScanActivity.intent(context)

        assertEquals(QRCodeScanActivity::class.java.name, started.component?.className)
    }

}

/** The gallery permission changed name at API 29, and the recovered code branched on exactly that. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QRCodeScanGalleryPermissionTest {

    @Test
    fun `on API 28 the legacy storage permission is the one asked for`() {
        assertEquals(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            QRCodeScanActivity.galleryPermission(),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QRCodeScanModernPermissionTest {

    @Test
    fun `on API 29 and up the media-images permission is the one asked for`() {
        assertEquals(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            QRCodeScanActivity.galleryPermission(),
        )
    }
}
