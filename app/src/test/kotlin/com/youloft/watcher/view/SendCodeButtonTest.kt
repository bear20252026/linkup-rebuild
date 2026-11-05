package com.youloft.watcher.view

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.R
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The countdown button, pinned as a timer: one minute in one-second ticks, a click that does nothing while
 * it runs, and the two callbacks the SMS sheet uses to lock and unlock the number field.
 *
 * The ticks are hand-fed through [SendCodeButton.Countdown]: `android.os.CountDownTimer` schedules its own
 * messages, and Robolectric never delivers them, so the alternative would be a countdown nothing asserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendCodeButtonTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Stands in for the platform timer so a test decides when a tick and the finish happen. */
    private class ManualCountdown : SendCodeButton.Countdown {
        var onTick: ((Long) -> Unit)? = null
        var onFinish: (() -> Unit)? = null
        var cancels = 0
        var starts = 0

        override fun start(onTick: (Long) -> Unit, onFinish: () -> Unit) {
            starts++
            this.onTick = onTick
            this.onFinish = onFinish
        }

        override fun cancel() {
            cancels++
        }

        fun tick(millisLeft: Long) = onTick!!(millisLeft)

        fun end() = onFinish!!()
    }

    private fun button(manual: ManualCountdown): SendCodeButton =
        SendCodeButton(context).apply {
            text = context.getString(R.string.send_code)
            countdownFactory = { manual }
            stopCountdown()
        }

    private fun counting(seconds: Long) = context.getString(R.string.resend_code_counting, seconds)

    @Test
    fun `each tick shows the seconds left and greys the label, and the end restores both`() {
        val manual = ManualCountdown()
        val button = button(manual)

        button.startCountdown()
        manual.tick(59_000L)
        assertEquals(counting(59L), button.text.toString())
        assertEquals(context.getColor(R.color.appSecondary), button.currentTextColor)

        manual.tick(31_000L)
        assertEquals(counting(31L), button.text.toString())

        manual.end()
        assertEquals(context.getString(R.string.resend_code), button.text.toString())
        assertEquals(context.getColor(R.color.appPrimary), button.currentTextColor)
    }

    @Test
    fun `a second start while counting reuses the window and never re-fires the start callback`() {
        var starts = 0
        val manual = ManualCountdown()
        val button = button(manual).apply { setCountdownListeners(onStart = { starts++ }, onFinish = null) }

        button.startCountdown()
        button.startCountdown()
        button.startCountdown()

        assertEquals(1, starts, "the guard is on the counting flag")
        assertEquals(1, manual.starts, "and the timer is not restarted")
    }

    @Test
    fun `clicks are swallowed only while it counts`() {
        var clicks = 0
        val manual = ManualCountdown()
        val button = button(manual)
        button.setOnClickListener { clicks++ }

        button.startCountdown()
        button.performClick()
        assertEquals(0, clicks, "`performClick` returns true without reaching the listener")

        manual.end()
        button.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `the two callbacks bracket the window, which is what locks and unlocks the number field`() {
        val events = mutableListOf<String>()
        val manual = ManualCountdown()
        val button = button(manual).apply {
            setCountdownListeners(onStart = { events += "start" }, onFinish = { events += "finish" })
        }

        button.startCountdown()
        manual.tick(10_000L)
        manual.end()

        assertEquals(listOf("start", "finish"), events)
    }

    @Test
    fun `stop cancels the window and lets the next start build a fresh one`() {
        val first = ManualCountdown()
        val button = button(first)

        button.startCountdown()
        button.stopCountdown()
        assertEquals(1, first.cancels)

        // `Z()` tears the timer down but leaves the counting flag alone -- only the window finishing
        // clears it -- so a restart right after a stop is still refused. The recovered sheet was always
        // dismissed together with the stop, which is why nobody noticed.
        val second = ManualCountdown()
        button.countdownFactory = { second }
        button.startCountdown()
        assertEquals(0, second.starts)

        first.end()
        button.startCountdown()
        assertEquals(1, second.starts, "once the window really ended, a fresh timer is built")
    }

    @Test
    fun `the idle wording, the counting format and the window are the recovered literals`() {
        assertEquals("重新发送（%d）", context.getString(R.string.resend_code_counting))
        assertEquals("重新发送", context.getString(R.string.resend_code))
        assertEquals(60_000L, SendCodeButton.DEFAULT_DURATION_MILLIS)
        assertEquals(1_000L, SendCodeButton.TICK_MILLIS)
    }
}
