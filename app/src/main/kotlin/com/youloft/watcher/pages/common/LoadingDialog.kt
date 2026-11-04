package com.youloft.watcher.pages.common

import android.content.Context
import android.view.WindowManager
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.databinding.DialogLoadingBinding

/**
 * Recovered from `pages.common.LoadingDialog`. The original is a subclass of the app's own dialog base
 * and plays `assets/loading.svga` in a 100dp white card; three behaviours are worth naming:
 *
 *  - the window is **not focusable**, so the sheet never steals the IME from whatever asked for it;
 *  - the animation starts on show and pauses on dismiss rather than being torn down;
 *  - a dismiss asked for *immediately* is postponed until the sheet has been visible for
 *    [minShownMillis] — the recovered default is 250ms, but `BaseFrameActivity.showLoading()` builds it
 *    with `0L`, so on the friend screens the floor is off. The rule is kept because other call sites
 *    do use it.
 */
class LoadingDialog @JvmOverloads constructor(
    context: Context,
    private val minShownMillis: Long = MIN_SHOWN_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) : BindingDialog<DialogLoadingBinding>(context) {

    private var shownAt = 0L

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onInit(binding: DialogLoadingBinding) {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        // The original paused the animation from its dismiss hook; doing it here means a dialog that
        // was never shown cannot trigger a lazy inflate on its way out.
        setOnDismissListener { runCatching { binding.ivLoading.pauseAnimation() } }
    }

    override fun show() {
        // The base class refuses to show under a dead activity; recording the timestamp after that
        // guard is what makes the minimum-display rule measurable.
        super.show()
        if (!isShowing) return
        shownAt = now()
        runCatching { binding.ivLoading.startAnimation() }
    }

    /** The recovered `b0(0)` path: wait out the minimum display time, then close asynchronously. */
    fun dismissAfterMinimum() {
        val elapsed = now() - shownAt
        if (minShownMillis <= 0L || elapsed >= minShownMillis) {
            dismissDelayed()
        } else {
            dismissDelayed(minShownMillis - elapsed)
        }
    }

    companion object {
        const val MIN_SHOWN_MILLIS = 250L
    }
}
