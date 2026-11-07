package com.youloft.watcher.view.lock

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.youloft.linkup.api.dto.QuestionBean
import com.youloft.watcher.R
import com.youloft.watcher.bean.lock.LockScreen

/**
 * `view/lock/LockScreenView` 的重建位：锁屏内容视图，悬浮窗宿主（WindowHelper 重建）的版面。
 * 结构与原版一致——锁标题 +（问题锁时）谜题选项/答案框 + 解锁按钮；视觉参数是本工程的
 * （原版 1000+ 行带背景图/SVGA，未整卡重建，DESIGN 记录），解锁语义对齐：答对（或无问题锁）
 * 触发 [onUnlock]，答错留在锁内并提示。
 */
class LockScreenView(context: Context) : FrameLayout(context) {

    /**
     * 一次性装配：数据 + 解锁回调（宿主把"答对"接到台账与命令回执上）。[onNeedsInput] 在答案框
     * 被点按时触发——悬浮窗默认 NOT_FOCUSABLE（原版 0x80000308），拿软键盘要宿主现场换 flags。
     */
    fun setData(
        screen: LockScreen,
        onUnlock: (LockScreen) -> Unit,
        onNeedsInput: () -> Unit = {},
    ) {
        removeAllViews()
        setBackgroundColor(BACKGROUND)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 128, 64, 64)
        }
        addView(
            column,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        column.addView(
            TextView(context).apply {
                text = screen.lockTitle ?: context.getString(R.string.locked_default_title)
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
        )

        val question = screen.question?.takeIf { it.content.isNotBlank() }
        var answerBox: EditText? = null
        if (question != null) {
            column.addView(
                TextView(context).apply {
                    text = question.content
                    textSize = 16f
                    setTextColor(0xFFE2FB6F.toInt())
                    setPadding(0, 48, 0, 16)
                },
            )
            question.options.orEmpty().filterNotNull().forEach { option ->
                column.addView(
                    TextView(context).apply {
                        text = "${option.label}. ${option.content}"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setPadding(0, 12, 0, 12)
                        setOnClickListener { answerBox?.setText(option.label) }
                    },
                )
            }
            answerBox = EditText(context).apply {
                hint = context.getString(R.string.lock_answer_hint)
                setOnClickListener { onNeedsInput() }
            }
            column.addView(answerBox)
        }
        column.addView(
            Button(context).apply {
                text = context.getString(R.string.lock_action_unlock)
                setOnClickListener {
                    if (!answersCorrect(question, answerBox?.text?.toString())) {
                        Toast.makeText(context, R.string.lock_answer_wrong, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    onUnlock(screen)
                }
            },
        )
    }

    companion object {
        /** 原版锁屏底色（color_324058 家族的全屏档）。 */
        const val BACKGROUND = 0xFF324058.toInt()

        /** 判题规则独立出来：悬浮窗宿主与应用内 LockedActivity 共用一个真源。 */
        fun answersCorrect(question: QuestionBean?, answer: String?): Boolean {
            if (question == null || question.content.isBlank()) return true
            if (answer.isNullOrBlank()) return false
            return answer.equals(question.correctAnswer, ignoreCase = true)
        }
    }
}
