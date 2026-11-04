package com.youloft.watcher.view

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The painter is arithmetic plus canvas work, and this machine's Robolectric cannot rasterize
 * faithfully (a drawn red square reads back as white), so the arithmetic is asserted directly and the
 * *plan* is re-rasterized here and handed to a real decoder — a picture that cannot be read back is not
 * the recovered picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchCodeQrPainterTest {

    private val shareUrl = "https://web-link.tttick.com/business/guard-report#/addMe?userid=1234567890&av=1.6.6"

    private val painter = MatchCodeQrPainter()

    @Test
    fun `the sizing arithmetic is the recovered formula`() {
        // 25 modules into a 288px sheet: reserve = min(288/27)*2 = 20, module = (288-20)/25 = 10.
        val layout = MatchCodeQrPainter.qrLayout(modulesW = 25, modulesH = 25, targetPx = 288, extraReservePx = 0)
        assertEquals(288, layout.canvasW)
        assertEquals(288, layout.canvasH)
        assertEquals(10, layout.modulePx)
        assertEquals(19, layout.offsetX)
        assertEquals(19, layout.offsetY)

        // A sheet smaller than the module count: the grid wins and there is nothing left over.
        val tiny = MatchCodeQrPainter.qrLayout(25, 25, 10, 0)
        assertEquals(25, tiny.canvasW)
        assertEquals(1, tiny.modulePx)
        assertEquals(0, tiny.offsetX)
    }

    @Test
    fun `only the three finder corners are skipped for the ring eye style`() {
        val side = 45
        assertTrue(MatchCodeQrPainter.inFinderArea(0, 0, side, side))
        assertTrue(MatchCodeQrPainter.inFinderArea(6, 6, side, side))
        assertTrue(MatchCodeQrPainter.inFinderArea(44, 0, side, side), "top right")
        assertTrue(MatchCodeQrPainter.inFinderArea(0, 44, side, side), "bottom left")
        assertTrue(!MatchCodeQrPainter.inFinderArea(44, 44, side, side), "no eye in the bottom right")
        assertTrue(!MatchCodeQrPainter.inFinderArea(7, 7, side, side))
        assertTrue(!MatchCodeQrPainter.inFinderArea(12, 12, side, side))
    }

    @Test
    fun `the eyes sit three and a half modules in from their corners`() {
        val layout = MatchCodeQrPainter.qrLayout(25, 25, 288, 0)
        val centers = MatchCodeQrPainter.eyeCenters(layout, 25, 25, layout.modulePx / 2f)

        assertEquals(3, centers.size)
        assertEquals(54f, centers[0].x)
        assertEquals(54f, centers[0].y)
        assertEquals(234f, centers[1].x, "19 + 25*10 - 35")
        assertEquals(54f, centers[1].y)
        assertEquals(54f, centers[2].x)
        assertEquals(234f, centers[2].y)
    }

    @Test
    fun `the plan describes a real code for the recovered payload`() {
        val plan = assertNotNull(painter.plan(shareUrl, 288))

        // 79 characters at EC Q is version 7: 45 modules a side.
        assertEquals(45, plan.modulesW)
        assertEquals(45, plan.modulesH)
        assertEquals(288, plan.canvasW)
        assertEquals(6, plan.modulePx, "reserve = (288/47)*2 = 12, module = (288-12)/45")
        assertEquals(9, plan.offsetX)
        assertEquals(0f, plan.moduleInsetPx, "fill ratio 1.0 means the module fills its cell")
        assertEquals(6f, plan.moduleSidePx)
        assertEquals(9f, plan.eyeDotRadiusPx)
        assertEquals(18f, plan.eyeRingRadiusPx)
        assertEquals(6f, plan.eyeRingStrokePx)

        // Every module the plan asks for stays inside the sheet.
        val cols = plan.moduleColRows.filterIndexed { index, _ -> index % 2 == 0 }
        val rows = plan.moduleColRows.filterIndexed { index, _ -> index % 2 == 1 }
        assertTrue(cols.isNotEmpty() && rows.isNotEmpty())
        assertTrue(rows.all { it in 0 until plan.modulesH })
        val furthest = max(cols.max()!!, rows.max()!!)
        assertTrue(furthest * plan.modulePx + plan.offsetX + plan.moduleSidePx <= plan.canvasW)
    }

    @Test
    fun `the plan rasterizes into a code a real reader can decode`() {
        val plan = assertNotNull(painter.plan(shareUrl, 288))

        assertEquals(
            shareUrl,
            decode(rasterize(plan, quietModules = 4)),
            "the recovered ring eye has to present the 1:1:3:1:1 profile",
        )
    }

    @Test
    fun `the size the screen actually asks for decodes too`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sizePx = (115f * context.resources.displayMetrics.density).roundToInt()

        val plan = assertNotNull(painter.plan(shareUrl, sizePx))

        assertEquals(sizePx, plan.canvasW)
        assertEquals(shareUrl, decode(rasterize(plan, quietModules = 4)))
    }

    @Test
    fun `the recovered reserve leaves about one and a half modules clear inside the sheet`() {
        // iMax3 takes two module sizes off the sheet, so the grid is centred with a margin of its own;
        // the transparent background then lets the white card finish the quiet zone.
        val plan = assertNotNull(painter.plan(shareUrl, 288))

        assertTrue(plan.offsetX in plan.modulePx..2 * plan.modulePx, "offset ${plan.offsetX} vs module ${plan.modulePx}")
        assertEquals(plan.offsetX, plan.offsetY)
    }

    @Test
    fun `an unrenderable payload returns nothing instead of crashing`() {
        // Far past what a QR version can hold; the original swallowed the exception and gave up.
        val tooBig = buildString { repeat(9_000) { append("https://link.tttick.com/") } }

        assertEquals(null, painter.plan(tooBig, 288))
        assertEquals(null, painter.render(tooBig, 288))
    }

    @Test
    fun `rendering produces a bitmap of the requested size`() {
        val bitmap = assertNotNull(painter.render(shareUrl, 288))

        assertEquals(288, bitmap.width)
        assertEquals(288, bitmap.height)
    }

    /** The plan plus a white frame, one integer per pixel — the part Canvas would have done. */
    private class Frame(val size: Int, val pixels: IntArray)

    private fun rasterize(plan: QrPlan, quietModules: Int): Frame {
        val pad = quietModules * plan.modulePx
        val size = plan.canvasW + pad * 2
        val pixels = IntArray(size * size) { Color.WHITE }

        fun set(x: Int, y: Int) {
            if (x in 0 until size && y in 0 until size) pixels[y * size + x] = Color.BLACK
        }

        var index = 0
        while (index < plan.moduleColRows.size) {
            val col = plan.moduleColRows[index]
            val row = plan.moduleColRows[index + 1]
            index += 2
            val left = (plan.offsetX + col * plan.modulePx + plan.moduleInsetPx + pad).roundToInt()
            val top = (plan.offsetY + row * plan.modulePx + plan.moduleInsetPx + pad).roundToInt()
            val side = plan.moduleSidePx.roundToInt()
            for (y in top until top + side) for (x in left until left + side) set(x, y)
        }

        plan.eyeCenters.forEach { center ->
            val cx = center.x + pad
            val cy = center.y + pad
            val reach = (plan.eyeRingRadiusPx + plan.eyeRingStrokePx / 2).roundToInt()
            for (y in (cy - reach).roundToInt()..(cy + reach).roundToInt()) {
                for (x in (cx - reach).roundToInt()..(cx + reach).roundToInt()) {
                    val distance = hypot(x + 0.5 - cx, y + 0.5 - cy)
                    val inDot = distance <= plan.eyeDotRadiusPx
                    val inRing = distance in
                        (plan.eyeRingRadiusPx - plan.eyeRingStrokePx / 2)..(plan.eyeRingRadiusPx + plan.eyeRingStrokePx / 2)
                    if (inDot || inRing) set(x, y)
                }
            }
        }
        return Frame(size, pixels)
    }

    private fun decode(frame: Frame): String =
        QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(frame.size, frame.size, frame.pixels))),
        ).text
}
