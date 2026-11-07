package com.youloft.watcher.view.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自绘滚轮，`com.contrarywind.view.WheelView`（Android-PickerView 家族）在本工程内的替代：
 * 原依赖走 jitpack 构建全挂（DESIGN 待验 74），这里按原版调用面自绘实现——竖直滚动 + 停靠、
 * fling、中心行高亮、上下 alpha 渐隐。样式面覆盖 [UnLockTimeSheet] 用到的每个 setter。
 *
 * 本工程选择记录在案：不逐像素复刻 PickerView 的装饰（曲率/分割线），只还原时间锁滚轮真正
 * 读到的那部分行为；`setDividerColor(0)` 在原版本来就是隐藏分割线。
 */
open class WheelView(context: Context) : View(context) {

    private val scroller = OverScroller(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var items: List<String> = emptyList()
    private var selectedItem = 0

    // 样式面（UnLockTimeFragment.Y 的调用序）
    private var textSizePx = 48f
    private var textOutColor = 0xFF40324058.toInt()
    private var textCenterColor = 0xFF324058.toInt()
    private var typeface: Typeface? = null
    private var alphaGradient = false
    private var visibleCount = 3
    private var lineSpacingMultiplier = 1.2f
    private var itemGravity = Gravity.CENTER

    private var lastTouchY = 0f
    private var itemHeight = 0
    private var listener: ((Int) -> Unit)? = null

    object Gravity {
        const val CENTER = 0
        const val LEFT = 3
        const val RIGHT = 5
    }

    fun setItems(newItems: List<String>) {
        items = newItems
        selectedItem = selectedItem.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        requestLayout()
        invalidate()
    }

    fun setCurrentItem(index: Int) {
        selectedItem = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        scrollTo(0, selectedItem * itemHeight)
        invalidate()
    }

    fun getCurrentItem(): Int = selectedItem

    fun setOnItemSelectedListener(listener: (Int) -> Unit) {
        this.listener = listener
    }

    // ---- 样式 setter（与 contrarywind API 同名，调用面照搬 UnLockTimeFragment.Y） ----

    fun setTextSize(sp: Float) {
        textSizePx = sp * resources.displayMetrics.scaledDensity
        computeItemHeight()
        requestLayout()
        invalidate()
    }

    fun setTextColorOut(color: Int) {
        textOutColor = color
        invalidate()
    }

    fun setTextColorCenter(color: Int) {
        textCenterColor = color
        invalidate()
    }

    fun setTypeface(face: Typeface?) {
        typeface = face
        invalidate()
    }

    fun setDividerColor(@Suppress("UNUSED_PARAMETER") color: Int) {
        // 原版时间锁传 0 隐藏分割线；本实现不画分割线，保留 setter 以维持调用面。
    }

    fun setAlphaGradient(enabled: Boolean) {
        alphaGradient = enabled
        invalidate()
    }

    fun setItemsVisibleCount(count: Int) {
        visibleCount = count.coerceAtLeast(2)
        requestLayout()
        invalidate()
    }

    fun setLineSpacingMultiplier(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        computeItemHeight()
        requestLayout()
        invalidate()
    }

    fun setGravity(gravity: Int) {
        itemGravity = gravity
        invalidate()
    }

    // ---- 布局与绘制 ----

    private fun computeItemHeight() {
        paint.textSize = textSizePx
        itemHeight = (paint.fontSpacing * lineSpacingMultiplier).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        computeItemHeight()
        val desired = itemHeight * visibleCount
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 换尺寸后停靠当前选中项，避免偏一格。
        scrollTo(0, selectedItem * itemHeight)
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || itemHeight == 0) return
        paint.typeface = typeface
        paint.textSize = textSizePx
        val center = height / 2f
        val first = ((scrollY - itemHeight) / itemHeight).coerceAtLeast(-1)
        val last = ((scrollY + height + itemHeight) / itemHeight).coerceIn(0, items.size - 1)
        val fadeSpan = (visibleCount / 2f) * itemHeight
        for (index in first..last) {
            if (index !in items.indices) continue
            val itemCenter = index * itemHeight + itemHeight / 2f - scrollY
            val distance = abs(itemCenter - center)
            val isCenterLine = distance < itemHeight / 2f
            paint.color = if (isCenterLine) textCenterColor else textOutColor
            if (alphaGradient && !isCenterLine && fadeSpan > 0f) {
                val fade = (1f - distance / fadeSpan).coerceIn(0.25f, 1f)
                paint.alpha = (paint.alpha * fade).toInt()
            }
            val baseline = itemCenter + (paint.fontMetrics.bottom - paint.fontMetrics.top) / 2 -
                paint.fontMetrics.bottom
            when (itemGravity) {
                Gravity.LEFT -> {
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(items[index], textSizePx, baseline, paint)
                }
                Gravity.RIGHT -> {
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(items[index], width - textSizePx, baseline, paint)
                }
                else -> {
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(items[index], width / 2f, baseline, paint)
                }
            }
        }
    }

    // ---- 滚动与停靠 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (lastTouchY - event.y).toInt()
                lastTouchY = event.y
                scrollBy(0, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                settle()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun settle() {
        val target = nearestItemScroll()
        scroller.startScroll(0, scrollY, 0, target - scrollY, SNAP_DURATION_MS)
        invalidate()
    }

    private fun nearestItemScroll(): Int =
        if (items.isEmpty()) 0 else (scrollY.toFloat() / itemHeight).roundToInt().coerceIn(0, items.size - 1) * itemHeight

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val newY = scroller.currY.coerceIn(minScroll(), maxScroll())
            scrollTo(0, newY)
            val index = if (itemHeight == 0) 0 else (newY.toFloat() / itemHeight).roundToInt()
            if (index != selectedItem && index in items.indices) {
                selectedItem = index
                listener?.invoke(selectedItem)
            }
            invalidate()
        }
    }

    private fun minScroll(): Int = 0

    private fun maxScroll(): Int = ((items.size - 1).coerceAtLeast(0)) * itemHeight

    private companion object {
        const val SNAP_DURATION_MS = 300
    }
}
