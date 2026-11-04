package com.youloft.watcher.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Recovered from `com.youloft.watcher.utils.z0.d(...)` (1.6.6 `utils.y0.d`) — the match-code QR
 * painter. Both builds have exactly one call site, the add-friend screen, always with the same
 * configuration, so that configuration is what this class hard-codes:
 *
 * 纠错 Q · 字符集 "utf-8" · 静区 0 · 前景黑 `0xFF000000` 背景透明 `0x00000000` ·
 * 定位眼样式 2（环 + 实心点，环外半径 3 模块、线宽 1 模块、中心点半径 1.5 模块）·
 * 模块形状 0（正方形，填充系数 1.0）。
 *
 * 原版另有几套样式（圆形/多边形模块、其他定位眼），1.6.6 已无调用点，所以这里只做真正被用到的那条；
 * 1.5.6 那张"头像嵌在码中心"的 logo 合图也随头像层一起被删了。
 *
 * 绘制分两步：[plan] 是纯数据版面（可断言、可被真 zxing 解码验证），[draw] 只是照着版面往 Canvas 上刷。
 * 这一步分开不是因为版面复杂，而是本机的 Robolectric 光栅化不可信——像素读回来是错的。
 */
class MatchCodeQrPainter : QrCodeRenderer {

    override fun render(content: String, sizePx: Int): Bitmap? {
        val layout = plan(content, sizePx) ?: return null
        return draw(layout)
    }

    /** The whole picture as arithmetic: nothing in here touches a Canvas. */
    internal fun plan(content: String, sizePx: Int): QrPlan? {
        val matrix = encode(content) ?: return null
        val modulesW = matrix.width
        val modulesH = matrix.height
        val layout = qrLayout(modulesW, modulesH, sizePx, EXTRA_RESERVE_PX)
        val module = layout.modulePx.toFloat()
        val half = module / 2f
        val inset = (1f - MODULE_FILL_RATIO) * module / 2f
        val side = module * MODULE_FILL_RATIO

        val modules = ArrayList<Int>(modulesW * modulesH)
        for (row in 0 until modulesH) {
            for (col in 0 until modulesW) {
                if (matrix.get(col, row) != DARK) continue
                if (inFinderArea(col, row, modulesW, modulesH)) continue
                modules += col
                modules += row
            }
        }
        return QrPlan(
            canvasW = layout.canvasW,
            canvasH = layout.canvasH,
            modulePx = layout.modulePx,
            offsetX = layout.offsetX,
            offsetY = layout.offsetY,
            modulesW = modulesW,
            modulesH = modulesH,
            moduleColRows = modules,
            eyeCenters = eyeCenters(layout, modulesW, modulesH, half),
            eyeDotRadiusPx = 3f * half * EYE_THICKNESS_RATIO,
            eyeRingRadiusPx = 6f * half,
            eyeRingStrokePx = module * EYE_THICKNESS_RATIO,
            moduleInsetPx = inset,
            moduleSidePx = side,
        )
    }

    private fun draw(p: QrPlan): Bitmap {
        val bitmap = Bitmap.createBitmap(p.canvasW, p.canvasH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(BACKGROUND)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = FOREGROUND
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val stroke = Paint().apply {
            color = FOREGROUND
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeWidth = p.eyeRingStrokePx
        }
        p.eyeCenters.forEach { center ->
            canvas.drawCircle(center.x, center.y, p.eyeDotRadiusPx, paint)
            canvas.drawCircle(center.x, center.y, p.eyeRingRadiusPx, stroke)
        }
        var index = 0
        while (index < p.moduleColRows.size) {
            val col = p.moduleColRows[index]
            val row = p.moduleColRows[index + 1]
            index += 2
            val left = p.offsetX + col * p.modulePx + p.moduleInsetPx
            val top = p.offsetY + row * p.modulePx + p.moduleInsetPx
            canvas.drawRect(left, top, left + p.moduleSidePx, top + p.moduleSidePx, paint)
        }
        return bitmap
    }

    private fun encode(content: String): ByteMatrix? = runCatching {
        Encoder.encode(
            content,
            ErrorCorrectionLevel.Q,
            mapOf(
                EncodeHintType.CHARACTER_SET to CHARSET,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            ),
        ).matrix
    }.getOrNull()

    companion object {
        const val FOREGROUND = 0xFF000000.toInt()
        const val BACKGROUND = 0x00000000
        const val CHARSET = "utf-8"
        const val DARK: Byte = 1
        const val FINDER_SIZE = 7

        /** The recovered call passed `0` for the reserve floor and `1.0f` for both ratios. */
        const val EXTRA_RESERVE_PX = 0
        const val EYE_THICKNESS_RATIO = 1.0f
        const val MODULE_FILL_RATIO = 1.0f

        /**
         * The sizing arithmetic of the original, verbatim:
         * `reserve = max(extra, min(w/(modules+2), h/(modules+2))) * 2`, then the module size is
         * whatever fits after that reserve is taken off, and the whole grid is centred.
         */
        internal fun qrLayout(modulesW: Int, modulesH: Int, targetPx: Int, extraReservePx: Int): QrLayout {
            val canvasW = maxOf(modulesW, targetPx)
            val canvasH = maxOf(modulesH, targetPx)
            val reserve = maxOf(extraReservePx, minOf(canvasW / (modulesW + 2), canvasH / (modulesH + 2))) * 2
            val module = minOf((canvasW - reserve) / modulesW, (canvasH - reserve) / modulesH)
            return QrLayout(
                canvasW = canvasW,
                canvasH = canvasH,
                modulePx = module,
                offsetX = (canvasW - module * modulesW) / 2,
                offsetY = (canvasH - module * modulesH) / 2,
            )
        }

        /** The three finder eyes sit 3.5 modules in from their corners — center of a 7×7 block. */
        internal fun eyeCenters(layout: QrLayout, modulesW: Int, modulesH: Int, half: Float): List<QrPoint> {
            val edge = FINDER_SIZE * half
            val right = layout.offsetX + modulesW * half * 2f
            val bottom = layout.offsetY + modulesH * half * 2f
            return listOf(
                QrPoint(layout.offsetX + edge, layout.offsetY + edge),
                QrPoint(right - edge, layout.offsetY + edge),
                QrPoint(layout.offsetX + edge, bottom - edge),
            )
        }

        /** The top-left, top-right and bottom-left 7×7 areas, exactly as the original excluded them. */
        internal fun inFinderArea(col: Int, row: Int, modulesW: Int, modulesH: Int): Boolean {
            val inTopLeft = col < FINDER_SIZE && row < FINDER_SIZE
            val inTopRight = col >= modulesW - FINDER_SIZE && row < FINDER_SIZE
            val inBottomLeft = col < FINDER_SIZE && row >= modulesH - FINDER_SIZE
            return inTopLeft || inTopRight || inBottomLeft
        }
    }
}

/** Where the grid lands inside the bitmap; pure arithmetic, so it can be asserted without drawing. */
data class QrLayout(
    val canvasW: Int,
    val canvasH: Int,
    val modulePx: Int,
    val offsetX: Int,
    val offsetY: Int,
)

/** A point in the sheet. Not `PointF`, so the plan can be reasoned about off the main thread. */
data class QrPoint(val x: Float, val y: Float)

/**
 * The picture as data. `moduleColRows` is a flat `[col, row, col, row…]` list of the dark modules the
 * sheet draws itself; the three finder areas are excluded because the ring eye replaces them.
 */
data class QrPlan(
    val canvasW: Int,
    val canvasH: Int,
    val modulePx: Int,
    val offsetX: Int,
    val offsetY: Int,
    val modulesW: Int,
    val modulesH: Int,
    val moduleColRows: List<Int>,
    val eyeCenters: List<QrPoint>,
    val eyeDotRadiusPx: Float,
    val eyeRingRadiusPx: Float,
    val eyeRingStrokePx: Float,
    val moduleInsetPx: Float,
    val moduleSidePx: Float,
)
