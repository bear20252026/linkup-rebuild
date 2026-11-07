package com.youloft.watcher.view.mine

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.youloft.watcher.databinding.ViewVipCardBinding

/**
 * 我的 Tab 的 VIP 卡（`view/mine/VipCard.java` 的重建骨架）：布局是原版 `view_vip_card.xml`，
 * 本 round 只重建显隐语义（非 VIP 隐藏，由 MineFragment 控制）与卡片渲染；权益内容、续费横幅与
 * 多帐号 VIP 分支（cl_multi_vip）跟 VIP 域的其余部分一起留给 VIP 卡。
 */
class VipCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ViewVipCardBinding.inflate(LayoutInflater.from(context), this, true)
}
