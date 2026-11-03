package com.youloft.watcher.view.decoration

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.FriendList
import com.youloft.watcher.R
import com.youloft.watcher.adapter.FriendListVAdapter

/**
 * Ported from `view.decoration.FriendListVDecoration`: rows whose `userId` is 0 are the app's
 * "experience" friend entries, and the badge is painted over them at a fixed offset.
 *
 * The bitmap is decoded inside `onDrawOver`, exactly like the recovered code. That is a per-frame
 * decode; keeping it verbatim is the safe choice for a recovery, and the fix belongs to a decision,
 * not to this port.
 */
class FriendListVDecoration : RecyclerView.ItemDecoration() {

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        val adapter = parent.adapter as? FriendListVAdapter ?: return
        if (adapter.itemCount < 1) return

        val offset = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BADGE_LEFT_DP,
            parent.resources.displayMetrics,
        ).toInt()
        val vertical = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BADGE_TOP_DP,
            parent.resources.displayMetrics,
        ).toInt()

        adapter.items.forEachIndexed { index, info ->
            if (info?.isExpFriend() != true) return@forEachIndexed
            val view = parent.findViewHolderForAdapterPosition(index)?.itemView ?: return
            c.drawBitmap(
                BitmapFactory.decodeResource(parent.resources, R.drawable.ic_friend_list_experience),
                (view.left + offset).toFloat(),
                (view.top - vertical).toFloat(),
                null as Paint?,
            )
        }
    }

    private companion object {
        const val BADGE_LEFT_DP = 34f
        const val BADGE_TOP_DP = 11f
    }
}

/** `FriendList.Info.isExpFriend()` from the recovered bean. */
internal fun FriendList.Info.isExpFriend(): Boolean = userId == 0L
