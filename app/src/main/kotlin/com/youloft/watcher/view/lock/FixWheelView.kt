package com.youloft.watcher.view.lock

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent

/**
 * `view/lock/FixWheelView` 逐行移植：滚轮嵌在可滚动容器（原版 NestedScrollView/BottomSheet）
 * 里时，按下即没收父级拦截，抬起/取消才归还——否则滚轮一动整页跟着滚。
 */
class FixWheelView(context: Context, attrs: AttributeSet? = null) : WheelView(context) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
}
