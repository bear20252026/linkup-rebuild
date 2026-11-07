package com.youloft.watcher.pages.lock

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.youloft.linkup.api.dto.LockInfo
import com.youloft.watcher.R
import com.youloft.watcher.bean.lock.LockScreen
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.view.lock.FixWheelView

/**
 * 时间锁发起流，`pages/lock/UnLockTimeFragment` 的 BottomSheet 版（原版是 UnlockSettingsActivity
 * 的内页）：时/分两枚 FixWheelView（时 0–23 右对齐、分 0–59 左对齐，样式调用序照搬
 * UnLockTimeFragment.Y：48sp、中心色 appPrimary、外侧色 appSecondaryVariant、
 * sf_pro_rounded_heavy_subset、可见 3 行、行距 1.2、alpha 渐隐）+ 四个档位（1分钟/10分钟/
 * 30分钟/1小时——原版 Android 档，iOS 15 分钟档依赖 LockInfo.isIos，重建 bean 无该字段、不编造）。
 *
 * 与原版的偏离记录：确认钮是点击式（原版 LockActionView"滑动确认锁定"）；时长恢复键
 * `lastLockDuration` 是本工程键（原版 CacheUtils.I() 的键名未反编译出来）。
 */
class UnLockTimeSheet(
    private val activity: LockListActivity,
    private val row: LockInfo,
    private val onConfirm: (screen: LockScreen, title: String) -> Unit,
) : BottomSheetDialog(activity) {

    private var hour = 0
    private var minute = 0
    private lateinit var confirmButton: TextView
    private lateinit var hourWheel: FixWheelView
    private lateinit var minuteWheel: FixWheelView

    override fun show() {
        val binding = com.youloft.watcher.databinding.SheetUnlockTimeBinding.inflate(activity.layoutInflater)
        setContentView(binding.root)

        restoreLastDuration()

        hourWheel = binding.wvHour.apply(style())
        minuteWheel = binding.wvMinute.apply(style())
        hourWheel.setGravity(com.youloft.watcher.view.lock.WheelView.Gravity.RIGHT)
        minuteWheel.setGravity(com.youloft.watcher.view.lock.WheelView.Gravity.LEFT)
        hourWheel.setItems((0..23).map { it.toString() })
        minuteWheel.setItems((0..59).map { it.toString() })
        hourWheel.setCurrentItem(hour)
        minuteWheel.setCurrentItem(minute)
        hourWheel.setOnItemSelectedListener { hour = it; refreshEnabled() }
        minuteWheel.setOnItemSelectedListener { minute = it; refreshEnabled() }

        confirmButton = binding.btnConfirmLock
        confirmButton.setOnClickListener { confirm(binding.etLockTitle.text?.toString().orEmpty()) }
        refreshEnabled()

        bindPresets(binding.presetGrid)

        super.show()
    }

    /** UnLockTimeFragment.Y 的样式调用序。 */
    private fun style(): FixWheelView.() -> Unit = {
        setTextSize(48f)
        setTextColorOut(0xFF40324058.toInt())
        setTextColorCenter(0xFF324058.toInt())
        setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sf_pro_rounded_heavy_subset))
        setDividerColor(0)
        setAlphaGradient(true)
        setItemsVisibleCount(3)
        setLineSpacingMultiplier(1.2f)
    }

    /** 原版四档（Android 列）：(0,1)(0,10)(0,30)(1,0)。 */
    private fun bindPresets(grid: android.widget.GridLayout) {
        val presets = listOf(
            Preset(R.string.unlock_duration_1, R.string.desc_unlock_time_type1, 0, 1),
            Preset(R.string.unlock_duration_2, R.string.desc_unlock_time_type2, 0, 10),
            Preset(R.string.unlock_duration_3, R.string.desc_unlock_time_type3, 0, 30),
            Preset(R.string.unlock_duration_4, R.string.desc_unlock_time_type4, 1, 0),
        )
        presets.forEach { preset ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundResource(R.drawable.bg_home_card)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
            }
            card.addView(
                TextView(activity).apply {
                    setText(preset.titleRes)
                    textSize = 14f
                    setTextColor(0xFF324058.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            card.addView(
                TextView(activity).apply {
                    setText(preset.descRes)
                    textSize = 12f
                    setTextColor(0xFF40324058.toInt())
                    setPadding(0, 4, 0, 0)
                },
            )
            card.setOnClickListener {
                hour = preset.hour
                minute = preset.minute
                hourWheel.setCurrentItem(hour)
                minuteWheel.setCurrentItem(minute)
                refreshEnabled()
            }
            grid.addView(card)
        }
    }

    /** 原版 V()：0 时 0 分不可锁。 */
    private fun refreshEnabled() {
        if (::confirmButton.isInitialized) {
            confirmButton.isEnabled = hour != 0 || minute != 0
            confirmButton.alpha = if (hour != 0 || minute != 0) 1f else 0.4f
        }
    }

    /** 原版 U()：时长 = (h*60+m)*60 秒；题库置空（问题锁走独立入口）。 */
    private fun confirm(title: String) {
        if (hour == 0 && minute == 0) return
        val seconds = (hour * 60L + minute) * 60L
        rememberLastDuration(seconds)
        Toast.makeText(activity, R.string.lock_state_locked, Toast.LENGTH_SHORT).show()
        onConfirm(
            LockScreen(
                userid = row.userId,
                nickName = row.nickname,
                avatar = row.avatar,
                lockDuration = seconds,
            ),
            title,
        )
        dismiss()
    }

    private fun restoreLastDuration() {
        val last = appGraph(activity).keyValueStores.shared.long(LAST_DURATION_KEY)
        hour = (last / 3600).toInt()
        minute = ((last % 3600) / 60).toInt()
    }

    private fun rememberLastDuration(seconds: Long) {
        appGraph(activity).keyValueStores.shared.putLong(LAST_DURATION_KEY, seconds)
    }

    private data class Preset(val titleRes: Int, val descRes: Int, val hour: Int, val minute: Int)

    private companion object {
        const val LAST_DURATION_KEY = "lastLockDuration"
    }
}
