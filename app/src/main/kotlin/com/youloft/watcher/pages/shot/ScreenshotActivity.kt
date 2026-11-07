package com.youloft.watcher.pages.shot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.ScreenshotData
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityScreenshotBinding
import kotlinx.coroutines.launch

/**
 * 实时截屏，166 新增卡：`api/screenshot/list`（对方设备状态行）+ `screenshot/done`（拉取一次截图）。
 * 截取端在原版走无障碍服务 + IM 命令 `screenShot`（step 0→3 回传 TOS 图 URL）；本 round 已把命令
 * 通道（`CmdType.SCREEN_SHOT`）与列表屏接上，无障碍采集端是独立权限卡（DESIGN 记录）。
 */
class ScreenshotActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScreenshotBinding
    private val rows = mutableListOf<ScreenshotData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rvScreenshot.layoutManager = LinearLayoutManager(this)
        binding.rvScreenshot.adapter = ScreenshotAdapter(rows)
        binding.ivBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val response = runCatching {
                appGraph(this@ScreenshotActivity).imApi.screenshotList()
            }.getOrNull()
            val list = response?.data?.list.orEmpty().filterNotNull()
            if (response?.isSuccess != true) {
                Toast.makeText(this@ScreenshotActivity, R.string.home_data_server_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            rows.clear()
            rows += list
            binding.rvScreenshot.adapter?.notifyDataSetChanged()
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ScreenshotActivity::class.java))
        }
    }
}

private class ScreenshotAdapter(private val rows: List<ScreenshotData>) :
    RecyclerView.Adapter<ScreenshotAdapter.Holder>() {
    class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(TextView(parent.context).apply {
            setPadding(48, 32, 48, 32)
            textSize = 15f
        })

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.view.text = (row.nickname ?: row.userId.toString()) +
            " · " + (row.client ?: "unknown") + " · " +
            (row.osVersion ?: "")
    }
}
