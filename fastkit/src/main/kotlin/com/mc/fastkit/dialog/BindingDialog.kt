package com.mc.fastkit.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.viewbinding.ViewBinding
import com.youloft.watcher.res166.R as BankR
import java.lang.reflect.ParameterizedType

/**
 * Rebuilt from the decompiled `com.mc.fastkit.dialog.BindingDialog`: it inflates its own ViewBinding
 * by reflecting on the generic argument, then hands the root to the dialog.
 *
 * This is the surface the recovered friend flow actually uses. The original `XDialog` base carried a
 * lifecycle host, keyboard following, animation sets and status-bar control (2000+ decompiled lines);
 * those are deliberately not reimplemented, so no screen should assume they exist.
 */
abstract class BindingDialog<V : ViewBinding>(context: Context) :
    Dialog(context, BankR.style.BaseDialog) {

    private val contentContainer = FrameLayout(context)

    private var bound: V? = null

    protected val binding: V
        get() = bound ?: inflateBinding().also {
            bound = it
            contentContainer.addView(it.root)
            setContentView(contentContainer)
            window?.setBackgroundDrawable(ColorDrawable(0))
            onInit(it)
        }

    init {
        setCanceledOnTouchOutside(true)
    }

    /** Called once, after the binding is inflated and installed. */
    protected abstract fun onInit(binding: V)

    override fun show() {
        // The recovered manager refused to show when the host activity was gone or the dialog was
        // already up; keeping that here so a stray second call cannot crash a screen.
        var candidate: Context? = context
        while (candidate is android.content.ContextWrapper && candidate !is android.app.Activity) {
            candidate = candidate.baseContext
        }
        val host = candidate as? android.app.Activity
        if (host != null && (host.isFinishing || host.isDestroyed)) return
        if (isShowing) return
        binding
        super.show()
    }

    /**
     * The recovered manager dismissed through a coroutine, i.e. asynchronously. A post keeps that
     * timing: a dialog that asks to close while it is still being built does not race `show()`.
     */
    fun dismissDelayed(millis: Long = 0L) {
        contentContainer.postDelayed(
            { if (isShowing) dismiss() },
            if (millis <= 0L) 0L else millis,
        )
    }

    private fun inflateBinding(): V {
        val type = generateSequence<Class<*>>(javaClass) { it.superclass }
            .firstNotNullOf { it.genericSuperclass as? ParameterizedType }
            .actualTypeArguments[0] as Class<*>
        val inflate = type.getMethod(
            "inflate",
            LayoutInflater::class.java,
            android.view.ViewGroup::class.java,
            Boolean::class.javaPrimitiveType,
        )
        @Suppress("UNCHECKED_CAST")
        return inflate.invoke(null, LayoutInflater.from(context), null, false) as V
    }
}
