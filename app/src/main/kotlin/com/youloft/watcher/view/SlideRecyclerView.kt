package com.youloft.watcher.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Scroller
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The horizontal swipe-to-reveal list the conversation centre used, ported from
 * `_raw/out_jadx_156/sources/com/youloft/watcher/view/SlideRecyclerView.java` rule for rule: the
 * 600 dp/s velocity thresholds, the child-at-position hit test, the "only one open row" behaviour and
 * the half-width snap. The item layout must be a two-child ViewGroup whose second child is the
 * revealed action pane — that is what `menuWidth` reads.
 */
class SlideRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = Scroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var lastX = 0f
    private var downY = 0f
    private var scrolling = false
    private var openForSwipe = false
    private var openItem: ViewGroup? = null
    private var touchChild = -1
    private var menuWidth = -1
    private var xVelocity = 0f
    private val hitRect = Rect()

    /** Closes whatever row is open. */
    fun closeOpenItem() {
        openForSwipe = false
        openItem?.let { item -> if (item.scrollX != 0) item.scrollTo(0, 0) }
    }

    fun hasOpenItem(): Boolean = openForSwipe

    fun track(event: MotionEvent) {
        (velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }).addMovement(event)
    }

    private fun dropTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun childAt(x: Int, y: Int): Int {
        val manager = layoutManager as? LinearLayoutManager ?: return -1
        val first = manager.findFirstVisibleItemPosition()
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) {
                child.getHitRect(hitRect)
                if (hitRect.contains(x, y)) return first + i
            }
        }
        return -1
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            openItem?.scrollTo(scroller.currX, scroller.currY)
            invalidate()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.toInt()
        val y = e.y.toInt()
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.abortAnimation()
                downX = x.toFloat(); lastX = x.toFloat(); downY = y.toFloat()
                touchChild = childAt(x, y)
                if (touchChild != -1) {
                    val previous = openItem
                    val child = getChildAt(touchChild - (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()) as? ViewGroup
                    openItem = child
                    if (previous != null && child != null && previous != child && previous.scrollX != 0) {
                        previous.scrollTo(0, 0)
                    }
                    menuWidth = if (child?.childCount == 2) child.getChildAt(1).width else -1
                }
            }

            MotionEvent.ACTION_UP -> dropTracker()

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.computeCurrentVelocity(1000)
                xVelocity = velocityTracker?.xVelocity ?: 0f
                val yVelocity = velocityTracker?.yVelocity ?: 0f
                val horizontalWins = Math.abs(xVelocity) > 600.0 && Math.abs(xVelocity) > Math.abs(yVelocity)
                if (horizontalWins || Math.abs(x - downX) >= touchSlop && Math.abs(x - downX) > Math.abs(y - downY)) {
                    scrolling = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!scrolling || touchChild == -1) {
            closeOpenItem()
            dropTracker()
            return super.onTouchEvent(e)
        }
        val x = e.x
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val item = openItem
                if (item != null && menuWidth != -1) {
                    val scrollX = item.scrollX
                    velocityTracker?.computeCurrentVelocity(1000)
                    when {
                        (velocityTracker?.xVelocity ?: 0f) < -600f -> {
                            openForSwipe = true
                            scroller.startScroll(scrollX, 0, menuWidth - scrollX, 0, Math.abs(menuWidth - scrollX))
                        }
                        (velocityTracker?.xVelocity ?: 0f) >= 600f -> {
                            openForSwipe = false
                            scroller.startScroll(scrollX, 0, -scrollX, 0, Math.abs(scrollX))
                        }
                        scrollX >= menuWidth / 2 -> {
                            openForSwipe = true
                            scroller.startScroll(scrollX, 0, menuWidth - scrollX, 0, Math.abs(menuWidth - scrollX))
                        }
                        else -> {
                            openForSwipe = false
                            scroller.startScroll(scrollX, 0, -scrollX, 0, Math.abs(scrollX))
                        }
                    }
                    invalidate()
                }
                menuWidth = -1
                scrolling = false
                touchChild = -1
                dropTracker()
            }

            MotionEvent.ACTION_MOVE -> {
                val item = openItem
                if (item != null && menuWidth != -1) {
                    val delta = lastX - x
                    if (item.scrollX + delta <= menuWidth && item.scrollX + delta > 0f) {
                        item.scrollBy(delta.toInt(), 0)
                    }
                    lastX = x
                }
            }
        }
        return true
    }
}
