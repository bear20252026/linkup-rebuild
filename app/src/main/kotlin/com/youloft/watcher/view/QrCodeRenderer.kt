package com.youloft.watcher.view

import android.graphics.Bitmap

/**
 * Draws the match-code QR the add-friend screen shows inside its white card.
 *
 * The original rendered it with `com.google.zxing` plus its own module painter
 * (`utils/z0.d(url, scale, size, margins, logo)`): error correction H, UTF-8, no quiet zone, rounded
 * modules, and an optional centred logo. That painter and the zxing dependency are their own unit of
 * work, so the production default draws nothing rather than inventing a different picture.
 */
fun interface QrCodeRenderer {
    fun render(content: String, sizePx: Int): Bitmap?

    object Pending : QrCodeRenderer {
        override fun render(content: String, sizePx: Int): Bitmap? = null
    }
}
