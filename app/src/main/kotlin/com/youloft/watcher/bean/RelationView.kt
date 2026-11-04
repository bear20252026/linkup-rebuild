package com.youloft.watcher.bean

import com.youloft.watcher.R

/**
 * One tile of the relation picker, recovered from `bean.friend.RelationView`: the tappable label,
 * the tick icon that fades in, the caption, and the corner badge that only shows when selected.
 */
data class RelationView(
    val layoutId: Int,
    val iconId: Int,
    val textId: Int,
    val starId: Int,
) {
    companion object {
        /** Declaration order matters: relation 1/2/3 map to the first three tiles, anything else to 其他. */
        val relationViews = listOf(
            RelationView(R.id.label_lover, R.id.iv_lover, R.id.tv_lover, R.id.iv_lover_label),
            RelationView(R.id.label_good_friend, R.id.iv_good_friend, R.id.tv_good_friend, R.id.iv_good_friend_label),
            RelationView(R.id.label_relatives, R.id.iv_relatives, R.id.tv_relatives, R.id.iv_relatives_label),
            RelationView(R.id.label_other, R.id.iv_other, R.id.tv_other, R.id.iv_other_label),
        )
    }
}
