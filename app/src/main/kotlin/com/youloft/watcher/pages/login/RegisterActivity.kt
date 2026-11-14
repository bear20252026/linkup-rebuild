package com.youloft.watcher.pages.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.watcher.data.appGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 账号密码注册：用户名 + 密码 + 管理员邀请码 → `POST api/user/register`。
 * 注册成功即自动登录（服务端返回 JWT + user），走正常 post-login 流程进主界面。
 *
 * 用户名输入做实时查重（`api/user/username/check`，400ms debounce，与注册同口径）——体验层；
 * 服务端的唯一索引与邀请码校验是正确性兜底。会话写失败（MMKV 类异常）**不进主界面**——
 * 无 token 的主界面只会退回登录循环；账号已在服务端建成，提示改走密码登录（登录不闪退，审计⑤）。
 */
class RegisterActivity : AppCompatActivity() {

    private val graph by lazy { appGraph(this) }

    private var registering = false

    private var usernameJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = android.widget.ScrollView(this).apply { setBackgroundColor(0xFFF5F6FA.toInt()) }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((48 * dp).toInt(), (128 * dp).toInt(), (48 * dp).toInt(), 0)
        }
        root.addView(column)
        setContentView(root)

        column.addView(android.widget.TextView(this).apply {
            text = "创建账号"
            textSize = 24f
            setTextColor(0xFF324058.toInt())
            setPadding(0, 0, 0, (32 * dp).toInt())
        })

        val etUsername = EditText(this).apply {
            hint = "用户名（3-32 字符）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        column.addView(etUsername, LinearLayoutParams().margin(bottom = (12 * dp).toInt()))

        val usernameHint = android.widget.TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        column.addView(usernameHint, LinearLayoutParams().margin(bottom = (4 * dp).toInt()))

        val etPassword = EditText(this).apply {
            hint = "密码（至少 6 位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        column.addView(etPassword, LinearLayoutParams().margin(bottom = (12 * dp).toInt()))

        val etInvite = EditText(this).apply {
            hint = "邀请码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        column.addView(etInvite, LinearLayoutParams().margin(bottom = (24 * dp).toInt()))

        column.addView(Button(this).apply {
            text = "注册并登录"
            setOnClickListener {
                val username = etUsername.text?.toString()?.trim() ?: ""
                val password = etPassword.text?.toString()?.trim() ?: ""
                val inviteCode = etInvite.text?.toString()?.trim() ?: ""
                if (username.isBlank() || password.isBlank() || inviteCode.isBlank()) {
                    Toast.makeText(this@RegisterActivity, "请填写全部字段", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                register(username, password, inviteCode)
            }
        }, LinearLayoutParams().margin(bottom = (16 * dp).toInt()))

        column.addView(android.widget.TextView(this).apply {
            text = "已有账号？去登录"
            textSize = 14f
            setTextColor(0xFF2C7BE5.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, 0)
            setOnClickListener { finish() }
        })

        etUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                usernameJob?.cancel()
                val name = s?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    usernameHint.visibility = View.GONE
                    return
                }
                usernameJob = lifecycleScope.launch {
                    delay(CHECK_DEBOUNCE_MS)
                    val response = runCatching { graph.loginRepository.usernameAvailable(name) }.getOrNull()
                    // 输入在等待里又变了，过期答案不上屏。
                    if (name != etUsername.text?.toString()?.trim()) return@launch
                    if (response == null || !response.isSuccess) {
                        usernameHint.visibility = View.GONE
                        return@launch
                    }
                    val available = response.data?.available == true
                    usernameHint.setTextColor(if (available) COLOR_OK else COLOR_BAD)
                    usernameHint.text = if (available) "该用户名可用" else (response.data?.reason ?: "该用户名不可用")
                    usernameHint.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun register(username: String, password: String, inviteCode: String) {
        if (registering) return
        registering = true
        lifecycleScope.launch {
            try {
                val response = graph.loginRepository.register(username, password, inviteCode)
                val user: com.youloft.linkup.api.dto.User? = response.data
                if (!response.isSuccess || user == null) {
                    Toast.makeText(this@RegisterActivity, response.msg ?: "注册失败", Toast.LENGTH_SHORT).show()
                    registering = false
                    return@launch
                }
                val stored = runCatching { graph.users.store(user) }
                if (stored.isFailure) {
                    registering = false
                    Toast.makeText(
                        this@RegisterActivity,
                        "会话初始化异常，请改用账号密码登录",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                com.youloft.watcher.pages.main.MainActivity.start(this@RegisterActivity)
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                registering = false
                Toast.makeText(this@RegisterActivity, "网络错误：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val CHECK_DEBOUNCE_MS = 400L
        private const val COLOR_OK = 0xFF2E9E5B.toInt()
        private const val COLOR_BAD = 0xFFD9480F.toInt()

        fun start(context: Context) {
            context.startActivity(Intent(context, RegisterActivity::class.java))
        }
    }
}

/** Layout params helper for programmatic views. */
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
