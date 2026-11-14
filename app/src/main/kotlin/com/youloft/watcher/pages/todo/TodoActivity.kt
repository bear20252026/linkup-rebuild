package com.youloft.watcher.pages.todo

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.youloft.linkup.api.dto.TodoModel
import com.youloft.linkup.api.dto.TodoRsp
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.pages.todo.TodoViewModel.Companion.QUADRANT_COUNT
import kotlinx.coroutines.launch

/**
 * 四象限待办（DESIGN 五点四十二，原 `pages/todo/TodoFragment` 的重建位——待验 80 的"入口保留
 * 不跳"在此闭环）：四组列表（重要紧急/重要不紧急/紧急不重要/不紧急不重要）+ 底部输入。
 * 条目点击切换完成态、行内"删"按钮删除、输入行选象限添加。数据只走 [TodoViewModel]
 * （`api/four_quadrant_list` 三路由），写成功后重拉全量——屏是台账的最新投影。
 */
class TodoActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private val chips = mutableListOf<TextView>()
    private var selectedQuadrant = 1
    private var sectionsColumn: android.widget.LinearLayout? = null

    private val viewModel: TodoViewModel by viewModels {
        TodoViewModel.factory(appGraph(this@TodoActivity).imApi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = android.widget.ScrollView(this).apply { setBackgroundColor(0xFFF5F6FA.toInt()) }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((48 * dp).toInt(), (96 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt())
        }
        root.addView(column)
        setContentView(root)

        column.addView(TextView(this).apply {
            text = getString(R.string.todo_title)
            textSize = 22f
            setTextColor(0xFF324058.toInt())
            setPadding(0, 0, 0, (24 * dp).toInt())
        })

        input = EditText(this).apply {
            hint = getString(R.string.todo_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        column.addView(input, LinearLayoutParams().margin(bottom = (12 * dp).toInt()))

        // 象限选择行：四个 chip，单选，默认第一个象限。
        val quadrantNames = listOf(
            getString(R.string.todo_q1),
            getString(R.string.todo_q2),
            getString(R.string.todo_q3),
            getString(R.string.todo_q4),
        )
        val chipRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        column.addView(chipRow, LinearLayoutParams().margin(bottom = (12 * dp).toInt()))
        quadrantNames.forEachIndexed { index, name ->
            val chip = TextView(this).apply {
                text = name
                textSize = 13f
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener {
                    selectedQuadrant = index + 1
                    renderChips(quadrantNames, dp)
                }
            }
            chips.add(chip)
            chipRow.addView(chip, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { setMargins(0, 0, (8 * dp).toInt(), 0) })
        }
        renderChips(quadrantNames, dp)

        sectionsColumn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        column.addView(sectionsColumn)

        column.addView(Button(this).apply {
            text = getString(R.string.todo_add)
            setOnClickListener {
                viewModel.add(input.text?.toString().orEmpty(), selectedQuadrant)
                input.setText("")
            }
        }, LinearLayoutParams().margin(bottom = (16 * dp).toInt()))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    state.error?.let {
                        Toast.makeText(this@TodoActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.consumeError()
                    }
                    renderSections(state.rsp, dp)
                }
            }
        }
        viewModel.start()
    }

    private fun renderChips(names: List<String>, dp: Float) {
        chips.forEachIndexed { index, chip ->
            val selected = index + 1 == selectedQuadrant
            chip.setTextColor(if (selected) 0xFF2C7BE5.toInt() else 0xFF666666.toInt())
            chip.paint.isFakeBoldText = selected
            chip.background = if (selected) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * dp
                    setColor(0xFFE8F1FD.toInt())
                }
            } else {
                null
            }
        }
        chips.getOrNull(selectedQuadrant - 1)?.requestLayout()
    }

    /** 每次状态变化整组重建：四组的行数是小数字，RecyclerView 在这里只添复杂度。 */
    private fun renderSections(rsp: TodoRsp?, dp: Float) {
        val column = sectionsColumn ?: return
        column.removeAllViews()
        val groups = listOf(
            getString(R.string.todo_q1) to rsp?.importantAndUrgent,
            getString(R.string.todo_q2) to rsp?.importantButNutAndUrgent,
            getString(R.string.todo_q3) to rsp?.notImportantButUrgent,
            getString(R.string.todo_q4) to rsp?.notImportantNotUrgent,
        )
        var any = false
        groups.forEachIndexed { index, (title, rows) ->
            val list = rows.orEmpty().filterNotNull()
            if (list.isEmpty()) return@forEachIndexed
            any = true
            column.addView(TextView(this).apply {
                text = "$title（${list.size}）"
                textSize = 15f
                setTextColor(0xFF324058.toInt())
                setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
            })
            list.forEach { entry ->
                column.addView(row(entry, dp))
            }
        }
        if (!any) {
            column.addView(TextView(this).apply {
                text = getString(R.string.todo_empty)
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                setPadding(0, (24 * dp).toInt(), 0, 0)
            })
        }
    }

    private fun row(entry: TodoModel, dp: Float): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val content = TextView(this).apply {
            text = (entry.content ?: "").take(40)
            textSize = 15f
            setTextColor(0xFF324058.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            paint.flags = if (entry.isFinish) paint.flags or Paint.STRIKE_THRU_TEXT_FLAG else paint.flags
            setOnClickListener { viewModel.toggle(entry) }
        }
        row.addView(content)
        val toggle = TextView(this).apply {
            text = if (entry.isFinish) "↩" else "✓"
            textSize = 16f
            setTextColor(if (entry.isFinish) 0xFF999999.toInt() else 0xFF2E9E5B.toInt())
            setPadding((16 * dp).toInt(), 0, 0, 0)
            setOnClickListener { viewModel.toggle(entry) }
        }
        row.addView(toggle)
        val delete = TextView(this).apply {
            text = getString(R.string.todo_delete)
            textSize = 13f
            setTextColor(0xFFD9480F.toInt())
            setPadding((16 * dp).toInt(), 0, 0, 0)
            setOnClickListener { viewModel.delete(entry.id) }
        }
        row.addView(delete)
        return row
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TodoActivity::class.java))
        }
    }
}

/** Layout params helper for programmatic views（RegisterActivity 同款）。 */
private class LinearLayoutParams {
    var top = 0
    var bottom = 0
    fun margin(bottom: Int): android.widget.LinearLayout.LayoutParams {
        this.bottom = bottom
        return android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, top, 0, bottom) }
    }
}
