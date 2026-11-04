package com.youloft.watcher.view

import android.graphics.Color
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The plan plus a white frame, one integer per pixel — the part Canvas would have done. This machine's
 * Robolectric cannot rasterize, so both the painter tests and the scan-decode tests build their picture
 * from the plan analytically and only then let a real reader (or a real decoder) look at it.
 */
class RasterizedQr(val size: Int, val pixels: IntArray)

internal fun rasterize(plan: QrPlan, quietModules: Int): RasterizedQr {
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
    return RasterizedQr(size, pixels)
}

