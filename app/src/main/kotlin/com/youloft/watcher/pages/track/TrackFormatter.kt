package com.youloft.watcher.pages.track

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.youloft.watcher.R
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackFormatter(private val context: Context) {

    private val numberTypeface by lazy {
        requireNotNull(ResourcesCompat.getFont(context, R.font.sf_pro_rounded_heavy_subset))
    }

    // Summary and move durations truncate; stay/unknown bubbles round instead.
    fun stayDuration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return when {
            minutes < 60 -> context.getString(R.string.format_minute, minutes.toString())
            minutes % 60 == 0L -> context.getString(R.string.format_hour, (minutes / 60).toString())
            else -> context.getString(
                R.string.format_hour_minute,
                (minutes / 60).toString(),
                (minutes % 60).toString(),
            )
        }
    }

    fun duration(milliseconds: Long, round: Boolean = true, showSeconds: Boolean = false): String {
        var days = milliseconds / 86_400_000
        var rest = milliseconds - days * 86_400_000
        var hours = rest / 3_600_000
        rest -= hours * 3_600_000
        var minutes = rest / 60_000
        val seconds = (rest - minutes * 60_000) / 1000
        if (round) {
            if (seconds >= 30) {
                minutes++
                if (minutes >= 60) {
                    hours++
                    minutes = 0
                }
            }
            // mf.a.c rounds half an hour up only when a day is already present.
            if (days > 0 && minutes >= 30) {
                hours++
                minutes = 0
            }
            if (hours >= 24) {
                days++
                hours = 0
            }
        }
        val parts = mutableListOf<String>()
        when {
            days > 0 -> {
                parts += context.getString(R.string.format_day, days)
                if (hours > 0) parts += context.getString(R.string.format_hour, hours.toString())
            }

            hours > 0 -> {
                parts += context.getString(R.string.format_hour, hours.toString())
                if (minutes > 0) parts += context.getString(R.string.format_minute2, minutes)
            }

            minutes > 0 -> parts += context.getString(R.string.format_minute, minutes.toString())
        }
        if (showSeconds && seconds > 0 && days <= 0) parts += context.getString(R.string.format_seconds, seconds)
        return parts.joinToString("")
    }

    // The summary includes 200m in the short-distance label; the move bubble does not.
    fun distance(meters: Double): String = when {
        meters <= 0.0 -> "0"
        meters <= 200.0 -> context.getString(R.string.less_than_200m)
        else -> oneDecimalKm(meters) + "km"
    }

    fun bubbleDistance(meters: Double): String =
        if (meters < 200.0) context.getString(R.string.less_than_200m)
        else BigDecimal(oneDecimalKm(meters)).stripTrailingZeros().toPlainString() + "km"

    private fun oneDecimalKm(meters: Double): String =
        String.format(Locale.US, "%.1f", meters / 1000.0)

    fun moveBubble(meters: Double, durationMilliseconds: Long): String =
        bubbleDistance(meters) + " • " + stayDuration(durationMilliseconds)

    fun clock(epochSeconds: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000L))

    fun emphasiseDigits(text: String): CharSequence {
        if (text.isEmpty()) return text
        val builder = SpannableStringBuilder(text)
        val sizePx = (TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            NUMBER_TEXT_SIZE_SP,
            context.resources.displayMetrics,
        ) + 0.5f).toInt()
        for (match in DIGIT_RUN.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            builder.setSpan(AbsoluteSizeSpan(sizePx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(NumberTypefaceSpan(numberTypeface), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    fun string(res: Int): String = context.getString(res)

    // TypefaceSpan(Typeface) requires API 28; this also preserves the original synthetic styles on API 26.
    private class NumberTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = applyTypeface(paint)

        override fun updateMeasureState(paint: TextPaint) = applyTypeface(paint)

        private fun applyTypeface(paint: TextPaint) {
            val missingStyle = (paint.typeface?.style ?: Typeface.NORMAL) and typeface.style.inv()
            if (missingStyle and Typeface.BOLD != 0) paint.isFakeBoldText = true
            if (missingStyle and Typeface.ITALIC != 0) paint.textSkewX = -0.25f
            paint.typeface = typeface
        }
    }

    companion object {
        private const val NUMBER_TEXT_SIZE_SP = 20f
        private val DIGIT_RUN = """\d+(\.\d+)?|\.\d+""".toRegex()
    }
}
