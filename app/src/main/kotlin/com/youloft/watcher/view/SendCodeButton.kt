package com.youloft.watcher.view

import android.content.Context
import android.content.res.ColorStateList
import android.os.CountDownTimer
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.youloft.watcher.R
import java.util.concurrent.TimeUnit

/**
 * The "重新发送" button in the SMS login dialog. Recovered `com.youloft.watcher.view.SendCodeButton`
 * (1.6.6, 200 lines): a 60-second `CountDownTimer` with a 1-second tick, the two formats
 * `resend_code_counting` / `resend_code`, and a colour swap while it runs.
 *
 * Two details are the point of the class, not decoration:
 *  - clicks are swallowed **while counting** (`performClick` returns early), on top of `enabled`;
 *  - the timer is cancelled when the view leaves the window, which is what keeps a dismissed dialog
 *    from ticking on. The dialog calls [stopCountdown] on dismiss — the recovered `Z()`.
 */
class SendCodeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val durationMillis = DEFAULT_DURATION_MILLIS
    private val countingFormat = context.getString(R.string.resend_code_counting)
    private val idleText = context.getString(R.string.resend_code)
    private val idleColor = context.getColor(R.color.appPrimary)
    private val countingColor = context.getColor(R.color.appSecondary)

    private val stateColors by lazy {
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), IntArray(0)),
            intArrayOf(idleColor, countingColor),
        )
    }

    private var counting = false
    private var countdown: Countdown? = null
    private var onCountingStart: (() -> Unit)? = null
    private var onCountingFinish: (() -> Unit)? = null

    /** Swapped by tests; the default is the platform timer with the recovered 60s / 1s window. */
    internal var countdownFactory: () -> Countdown = { TimerCountdown(durationMillis, TICK_MILLIS) }

    init {
        rebuildTimer()
        setTextColor(stateColors)
    }

    /** `a0(onStart, onFinish)` — the dialog uses these to grey out and re-enable the phone field. */
    fun setCountdownListeners(onStart: (() -> Unit)?, onFinish: (() -> Unit)?) {
        onCountingStart = onStart
        onCountingFinish = onFinish
    }

    /** `c0()`: idempotent while counting, which is why a double send cannot restart the countdown. */
    @Synchronized
    fun startCountdown() {
        if (counting) return
        if (countdown == null) countdown = countdownFactory()
        counting = true
        countdown?.start(::renderTick, ::finish)
        onCountingStart?.invoke()
    }

    /** `Z()`: cancel and forget, so the next start builds a fresh timer. */
    @Synchronized
    fun stopCountdown() {
        countdown?.cancel()
        countdown = null
    }

    /** `onTick` — the seconds remaining in the recovered format, greyed while it runs. */
    private fun renderTick(millisUntilFinished: Long) {
        text = String.format(countingFormat, TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished))
        setTextColor(countingColor)
    }

    /** `onFinish` — the idle wording, the idle colour, and the callback that unlocks the number field. */
    private fun finish() {
        text = idleText
        counting = false
        setTextColor(idleColor)
        onCountingFinish?.invoke()
    }

    /** `b0()` */
    private fun rebuildTimer() {
        countdown?.cancel()
        countdown = countdownFactory()
    }

    /**
     * What `startCountdown` drives. Production is `android.os.CountDownTimer`; tests hand-feed ticks,
     * because Robolectric never delivers that class's messages — and a countdown that cannot be advanced
     * cannot pin the label arithmetic or the field lock that hangs off it.
     */
    internal interface Countdown {
        fun start(onTick: (Long) -> Unit, onFinish: () -> Unit)

        fun cancel()
    }

    private class TimerCountdown(
        private val millis: Long,
        private val interval: Long,
    ) : Countdown {
        private var timer: CountDownTimer? = null

        override fun start(onTick: (Long) -> Unit, onFinish: () -> Unit) {
            timer?.cancel()
            timer = object : CountDownTimer(millis, interval) {
                override fun onTick(millisUntilFinished: Long) = onTick(millisUntilFinished)

                override fun onFinish() = onFinish()
            }.start()
        }

        override fun cancel() {
            timer?.cancel()
            timer = null
        }
    }

    override fun performClick(): Boolean {
        if (counting) return true
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        countdown?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 60_000L
        const val TICK_MILLIS = 1_000L
    }
}
