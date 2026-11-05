package com.youloft.watcher.ext

import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView

/**
 * The recovered screens made a *substring of an existing string* clickable — the agreement line, the
 * "忘记密码" style affordances — through the `com.drake.spannable` helper (`o7.b.q(text, target, …)`),
 * which is not a dependency here. This is that one behaviour, and only that one: colour, optional bold,
 * no underline, every occurrence of the literal.
 *
 * The original also swallowed a missing target silently, so an empty or absent `target` returns the
 * text untouched rather than throwing — the caller decides whether to ask for the span at all.
 */
fun CharSequence.withClickableSpan(
    target: String,
    color: Int,
    bold: Boolean = false,
    onClick: (View) -> Unit,
): CharSequence {
    if (target.isEmpty() || !toString().contains(target)) return this
    val spannable = SpannableString(this)
    var from = indexOf(target)
    while (from >= 0) {
        val to = from + target.length
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onClick(widget)

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = color
                    ds.isUnderlineText = false
                    if (bold) {
                        ds.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                }
            },
            from,
            to,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (bold) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        from = indexOf(target, to)
    }
    return spannable
}

/** The original set `LinkMovementMethod` on the text view before the spans could be tapped. */
fun TextView.enableSpanClicks() {
    movementMethod = LinkMovementMethod.getInstance()
}
