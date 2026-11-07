package com.youloft.watcher.pages.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.api.dto.QuestionBean
import com.youloft.watcher.R
import com.youloft.watcher.bean.lock.LockScreen
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.im.LockCommandBridge
import kotlinx.coroutines.launch

/**
 * 被锁设备的锁屏（`utils/WindowHelper` 重建位的 Activity 版）：全屏不透明、显示锁标题与
 * （问题锁时）谜题，答对（或无问题锁、时长到）→ `api/relation/unlock` + `lockType=3` 命令回执。
 *
 * 与原版 WindowManager TYPE_APPLICATION_OVERLAY 悬浮窗的偏离，记录在 DESIGN：悬浮窗权限
 * （`SYSTEM_ALERT_WINDOW`）要用户单独授权，授权路径是独立卡；本版在应用内总能成立，拿到
 * overlay 权限后由悬浮窗宿主接管（`LockCommandBridge.onLock` 的调用方按权限分流）。
 */
class LockedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val screen = (intent.getSerializableExtra(EXTRA_LOCK) as? LockScreen) ?: LockScreen()
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF324058.toInt()) }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 128, 64, 64)
        }
        root.addView(
            column,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER,
            ),
        )
        val title = TextView(this).apply {
            text = screen.lockTitle ?: getString(R.string.locked_default_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        column.addView(title)

        val question = screen.question
        var answerBox: EditText? = null
        if (question != null && question.content.isNotBlank()) {
            column.addView(
                TextView(this).apply {
                    text = question.content
                    textSize = 16f
                    setTextColor(0xFFE2FB6F.toInt())
                    setPadding(0, 48, 0, 16)
                },
            )
            question.options.orEmpty().filterNotNull().forEach { option ->
                column.addView(
                    TextView(this).apply {
                        text = "${option.label}. ${option.content}"
                        textSize = 15f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(0, 12, 0, 12)
                        setOnClickListener { answerBox?.setText(option.label) }
                    },
                )
            }
            answerBox = EditText(this).apply { hint = getString(R.string.lock_answer_hint) }
            column.addView(answerBox)
        }

        column.addView(
            Button(this).apply {
                text = getString(R.string.lock_action_unlock)
                setOnClickListener { onUnlock(screen, question, answerBox?.text?.toString()) }
            },
        )
        setContentView(root)
    }

    private fun onUnlock(screen: LockScreen, question: QuestionBean?, answer: String?) {
        if (question != null && question.content.isNotBlank() &&
            !answer.isNullOrBlank() &&
            !answer.equals(question.correctAnswer, ignoreCase = true)
        ) {
            Toast.makeText(this, R.string.lock_answer_wrong, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                appGraph(this@LockedActivity).imApi.relationUnlock(
                    payload = mutableMapOf("friendId" to screen.userid),
                )
            }
            Toast.makeText(this@LockedActivity, R.string.lock_state_free, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_LOCK = "lockScreen"

        fun start(context: Context, screen: LockScreen) {
            context.startActivity(
                Intent(context, LockedActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_LOCK, screen),
            )
        }
    }
}
