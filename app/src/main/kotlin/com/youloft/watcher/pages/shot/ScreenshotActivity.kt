package com.youloft.watcher.pages.shot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.ScreenshotData
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.im.ScreenshotCommandBridge
import com.youloft.watcher.databinding.ActivityScreenshotBinding
import kotlinx.coroutines.launch

/**
 * 实时截屏，166 新增卡：`api/screenshot/list`（对方设备状态行）+ 行内"截图"发起一轮截屏。
 * 命令通道（`screenShot`，step 0→3 回执）由 [ScreenshotCommandBridge] 承担，本屏是发起端
 * 宿主；采集端（无障碍服务 + takeScreenshot + 上传）也在桥里闭环——两张卡在本轮合龙。
 */
class ScreenshotActivity : AppCompatActivity(), ScreenshotReplies {
    private lateinit var binding: ActivityScreenshotBinding
    private val rows = mutableListOf<ScreenshotData>()
    private var bridge: ScreenshotCommandBridge? = null

    override val screenshotReplies: kotlinx.coroutines.flow.SharedFlow<com.youloft.watcher.bean.CmdScreenShot>
        get() = bridge?.replies ?: kotlinx.coroutines.flow.MutableSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rvScreenshot.layoutManager = LinearLayoutManager(this)
        binding.rvScreenshot.adapter = ScreenshotAdapter(rows, ::shoot)
        binding.ivBack.setOnClickListener { finish() }
        val graph = appGraph(this)
        bridge = ScreenshotCommandBridge(
            context = this,
            imClient = graph.imClient,
            api = graph.imApi,
            scope = lifecycleScope,
            // 采集请求发送方守门（审计 P0-3）：截屏只认确认过的好友，别人的请求丢弃。
            trusted = { peer -> graph.peerGuard.trusted(peer) },
            myUserId = { graph.users.current().userId },
        ).also { it.start() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val response = runCatching {
                appGraph(this@ScreenshotActivity).imApi.screenshotList()
            }.getOrNull()
            if (response?.isSuccess != true) {
                Toast.makeText(this@ScreenshotActivity, R.string.home_data_server_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            rows.clear()
            rows += response.data?.list.orEmpty().filterNotNull()
            binding.rvScreenshot.adapter?.notifyDataSetChanged()
        }
    }

    private fun shoot(row: ScreenshotData) {
        val holder = bridge ?: return
        ScreenshotDialog(
            this,
            row,
            launcher = object : ScreenshotDialog.ScreenshotCommandBridgeLauncher {
                override fun request(peerUserId: Long) = holder.request(peerUserId)
                override fun reportDone(peerUserId: Long, picUrl: String) = holder.reportDone(peerUserId, picUrl)
            },
            scope = lifecycleScope,
        ).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ScreenshotActivity::class.java))
        }
    }
}

private class ScreenshotAdapter(
    private val rows: List<ScreenshotData>,
    private val onShoot: (ScreenshotData) -> Unit,
) : RecyclerView.Adapter<ScreenshotAdapter.Holder>() {
    class Holder(val root: LinearLayout, val text: TextView, val action: Button) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val text = TextView(context).apply {
            textSize = 15f
            setTextColor(0xFF324058.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val action = Button(context).apply { setText(R.string.screenshot_action) }
        row.addView(text)
        row.addView(action)
        return Holder(row, text, action)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.text.text = (row.nickname ?: row.userId.toString()) +
            " · " + (row.client ?: "") + " · " + (row.osVersion ?: "")
        holder.action.setOnClickListener { onShoot(row) }
    }
}
