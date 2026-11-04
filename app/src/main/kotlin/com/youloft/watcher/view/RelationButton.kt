package com.youloft.watcher.view

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.youloft.watcher.R
import com.youloft.watcher.bean.RelationView
import com.youloft.watcher.databinding.ViewRelationButtonBinding

/**
 * The four-tile relation picker recovered from `view.RelationButton`. Selecting a tile widens it
 * from 80dp to 100dp, tints it with the brand colour, fades in its tick and shows its corner badge;
 * the others animate back.
 */
class RelationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = ViewRelationButtonBinding.inflate(LayoutInflater.from(context), this, true)

    private var currentRelation = 0

    var onRelationChanged: (Int) -> Unit = {}

    init {
        binding.labelLover.setOnClickListener { setRelation(RELATION_LOVER) }
        binding.labelGoodFriend.setOnClickListener { setRelation(RELATION_FRIEND) }
        binding.labelRelatives.setOnClickListener { setRelation(RELATION_FAMILY) }
        binding.labelOther.setOnClickListener { setRelation(RELATION_OTHER) }
    }

    fun setRelation(relation: Int) {
        if (currentRelation == relation) return
        currentRelation = relation
        onRelationChanged(relation)
        select(
            when (relation) {
                RELATION_LOVER -> R.id.label_lover
                RELATION_FRIEND -> R.id.label_good_friend
                RELATION_FAMILY -> R.id.label_relatives
                else -> R.id.label_other
            },
        )
    }

    private fun select(selectedId: Int) {
        RelationView.relationViews.forEach { tile ->
            val selected = tile.layoutId == selectedId
            findViewById<ImageView>(tile.starId).visibility = if (selected) View.VISIBLE else View.GONE

            val label = findViewById<ViewGroup>(tile.layoutId)
            val icon = findViewById<ImageView>(tile.iconId)
            icon.visibility = if (selected) View.VISIBLE else View.GONE

            val params = label.layoutParams
            val width = ValueAnimator.ofInt(
                params.width,
                dp(if (selected) SELECTED_WIDTH_DP else UNSELECTED_WIDTH_DP),
            ).apply {
                duration = ANIMATION_MILLIS
                addUpdateListener {
                    params.width = it.animatedValue as Int
                    label.layoutParams = params
                }
            }

            val fromColor = label.backgroundTintList?.defaultColor
                ?: (label.background as? ColorDrawable)?.color
                ?: 0
            val tint = ValueAnimator.ofArgb(
                fromColor,
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.colorBrand else R.color.colorCardVariant,
                ),
            ).apply {
                duration = ANIMATION_MILLIS
                addUpdateListener {
                    label.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int)
                }
            }

            val alpha = ValueAnimator.ofFloat(
                if (selected) 0f else 1f,
                if (selected) 1f else 0f,
            ).apply {
                duration = ANIMATION_MILLIS
                addUpdateListener { icon.alpha = it.animatedValue as Float }
            }

            AnimatorSet().apply {
                playTogether(width, tint, alpha)
                start()
            }
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val RELATION_LOVER = 1
        const val RELATION_FRIEND = 2
        const val RELATION_FAMILY = 3
        const val RELATION_OTHER = 4
        const val SELECTED_WIDTH_DP = 100
        const val UNSELECTED_WIDTH_DP = 80
        const val ANIMATION_MILLIS = 300L
    }
}
