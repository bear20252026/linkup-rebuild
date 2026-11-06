package com.youloft.watcher.pages.track

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.watcher.R
import com.youloft.watcher.databinding.ItemTrackRecordBinding
import com.youloft.watcher.databinding.ItemTrackRecordSummaryBinding

class TrackSummaryAdapter(
    items: MutableList<TrackRow.Summary>,
    private val formatter: TrackFormatter,
) : BaseQuickAdapter<TrackRow.Summary, QuickViewHolder>(items) {
    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int) =
        QuickViewHolder(R.layout.item_track_record_summary, parent)

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: TrackRow.Summary?) {
        if (item == null) return
        val binding = ItemTrackRecordSummaryBinding.bind(holder.itemView)
        binding.tvStayCount.text = item.stayCount.toString()
        binding.tvStayDuration.text = formatter.emphasiseDigits(
            if (item.stayDurationMillis <= 0) "0" else formatter.stayDuration(item.stayDurationMillis),
        )
        binding.tvDistance.text = formatter.emphasiseDigits(formatter.distance(item.distanceMeters))
        binding.ivVip.visibility = if (item.vipBadgeVisible) View.VISIBLE else View.GONE
    }
}

class TrackPointAdapter(
    items: MutableList<TrackRow.Point>,
    private val onSelected: (TrackRecordBean.Track) -> Unit,
    private val onCleared: () -> Unit,
    private val onNoNewLocationClicked: () -> Unit,
) : BaseQuickAdapter<TrackRow.Point, QuickViewHolder>(items) {
    var selectedTimePeriod: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, this.items.size)
        }

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int) =
        QuickViewHolder(R.layout.item_track_record, parent)

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: TrackRow.Point?) {
        if (item == null) return
        val binding = ItemTrackRecordBinding.bind(holder.itemView)
        val view = item.view
        val ctx = binding.root.context
        val selected = selectedTimePeriod == view.track.timePeriod
        binding.root.isSelected = selected
        binding.tvTime.text = view.time
        binding.ivPoint.setImageResource(
            when (view.point.icon) {
                PointIcon.STAY -> R.drawable.ic_track_record_list_point_stay
                PointIcon.LEAVE, PointIcon.PLAIN -> R.drawable.ic_track_record_list_point_blue
                PointIcon.RECORDING -> R.drawable.ic_track_record_list_point_recording
                PointIcon.LOST_RED -> R.drawable.ic_track_record_list_point_red
            },
        )
        val label = view.point.labelRes
        binding.tvPointType.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.tvPointType.text = if (label == null || label == PointVisual.BLANK_LABEL) "" else ctx.getString(label)
        binding.arriveLayout.visibility = if (view.point.address == null) View.GONE else View.VISIBLE
        binding.tvAddress.text = view.point.address.orEmpty()

        val bubble = binding.tvLineTypeContent
        bubble.setOnClickListener(null)
        bubble.isClickable = false
        bubble.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(bubble, null)
        bubble.compoundDrawablePadding = 0
        bubble.text = ""
        bubble.backgroundTintList = null
        val line = view.line
        listOf(binding.timeLine, binding.ivLineType, bubble).forEach {
            it.visibility = if (line == null) View.GONE else View.VISIBLE
        }
        if (line != null) {
            bubble.text = line.text
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (line.kind in setOf(LineKind.NO_LOCATION, LineKind.NO_NEW_LOCATION)) 12f else 14f)
            if (line.opensReasonDialog) bubble.setOnClickListener { onNoNewLocationClicked() }
            when (line.kind) {
                LineKind.STAY -> {
                    binding.timeLine.setBackgroundResource(R.color.colorMale)
                    binding.ivLineType.setImageResource(R.drawable.ic_track_record_time_line_stay)
                    paint(bubble, ctx.color(if (selected) R.color.white else R.color.colorMale), selected)
                }
                LineKind.MOVE -> {
                    bubble.compoundDrawablePadding = ctx.dp(4)
                    bubble.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        line.speed?.let { speedDrawable(ctx, it) }, null, null, null,
                    )
                    binding.timeLine.setBackgroundColor(if (selected) ctx.color(R.color.colorMale) else LINE_GREY)
                    binding.ivLineType.setImageResource(
                        if (selected) R.drawable.ic_track_record_time_line_move_selected else R.drawable.ic_track_record_time_line_move,
                    )
                    if (selected) TextViewCompat.setCompoundDrawableTintList(bubble, ColorStateList.valueOf(Color.WHITE))
                    paint(bubble, if (selected) Color.WHITE else ctx.color(R.color.appSecondary), selected)
                }
                LineKind.NO_LOCATION -> {
                    binding.timeLine.setBackgroundResource(R.drawable.img_track_record_time_line_dash)
                    binding.ivLineType.setImageResource(R.drawable.ic_track_record_time_line_no_loc)
                    paint(bubble, LINE_RED, false)
                }
                LineKind.NO_NEW_LOCATION -> {
                    binding.timeLine.setBackgroundResource(R.drawable.img_track_record_time_line_dash)
                    binding.ivLineType.setImageResource(R.drawable.ic_track_record_time_line_warning)
                    bubble.compoundDrawablePadding = ctx.dp(2)
                    bubble.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_right_primary_5_9, 0)
                    TextViewCompat.setCompoundDrawableTintList(bubble, ColorStateList.valueOf(LINE_RED))
                    paint(bubble, LINE_RED, false)
                }
                LineKind.UNKNOWN -> {
                    binding.timeLine.setBackgroundColor(LINE_GREY)
                    binding.ivLineType.setImageResource(R.drawable.ic_track_record_time_line_unknown)
                    paint(bubble, ctx.color(R.color.appSecondary), false)
                }
            }
        }
        binding.root.setOnClickListener {
            if (view.selectable) onSelected(view.track) else onCleared()
        }
    }

    private fun paint(bubble: TextView, textColor: Int, tinted: Boolean) {
        bubble.setTextColor(textColor)
        bubble.backgroundTintList = if (tinted) ColorStateList.valueOf(bubble.context.color(R.color.colorMale)) else null
    }

    private fun speedDrawable(ctx: Context, icon: SpeedIcon) = ContextCompat.getDrawable(
        ctx,
        when (icon) {
            SpeedIcon.WALK -> R.drawable.ic_user_speed_walk
            SpeedIcon.BICYCLE -> R.drawable.ic_user_speed_bicycle
            SpeedIcon.CAR -> R.drawable.ic_user_speed_car
            SpeedIcon.TRAIN -> R.drawable.ic_user_speed_train
            SpeedIcon.PLANE -> R.drawable.ic_user_speed_plane
        },
    )

    private fun Context.color(res: Int) = ContextCompat.getColor(this, res)
    private fun Context.dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private val LINE_GREY = Color.parseColor("#CCCFD5")
        private val LINE_RED = Color.parseColor("#FF7A7A")
    }
}
