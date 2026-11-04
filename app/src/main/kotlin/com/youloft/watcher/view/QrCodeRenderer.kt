package com.youloft.watcher.view

import android.graphics.Bitmap

/**
 * Draws the match-code QR the add-friend screen shows inside its white card.
 *
 * The original rendered it with `com.google.zxing` plus its own module painter
 * (`utils/z0.d(url, scale, size, margins, logo)`); [MatchCodeQrPainter] is that painter.
 */
fun interface QrCodeRenderer {
    fun render(content: String, sizePx: Int): Bitmap?
}
