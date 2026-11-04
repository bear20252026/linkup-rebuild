package com.youloft.watcher.pages.common

import android.content.Context
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.R
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The loading sheet has one non-obvious rule — an immediate dismiss is postponed until it has actually
 * been visible — so the clock is injected and that rule is the subject of these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadingDialogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `the sheet shows the recovered card and refuses input focus`() {
        val dialog = LoadingDialog(context, minShownMillis = 0L)
        dialog.show()

        assertNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<ImageView>(R.id.iv_loading))
        val flags = dialog.window!!.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0, "the original sets window flag 8")

        dialog.dismissAfterMinimum()
        idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `the twenty-five hundredths floor postpones an immediate dismiss`() {
        var clock = 1_000L
        val dialog = LoadingDialog(context, minShownMillis = LoadingDialog.MIN_SHOWN_MILLIS, now = { clock })
        dialog.show()

        clock = 1_100L
        dialog.dismissAfterMinimum()
        assertTrue(dialog.isShowing, "only 100ms of the 250ms floor had elapsed")

        advance(200)
        assertFalse(dialog.isShowing, "the remaining 150ms was the posted delay")
    }

    @Test
    fun `once the floor is paid the sheet closes straight away`() {
        var clock = 1_000L
        val dialog = LoadingDialog(context, minShownMillis = LoadingDialog.MIN_SHOWN_MILLIS, now = { clock })
        dialog.show()

        clock = 2_000L
        dialog.dismissAfterMinimum()
        advance(1)

        assertFalse(dialog.isShowing)
    }
}
