package com.youloft.watcher.pages.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityEmptyLoginBinding
import com.youloft.watcher.ext.enableSpanClicks
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.ext.withClickableSpan
import com.youloft.watcher.pages.login.dialog.PrivacyPolicyDialog

/**
 * Recovered `pages.login.EmptyLoginActivity` — the first-launch privacy gate. The original launcher
 * (`SplashActivity`, not rebuilt) routes here while `isAgreePrivacyPolicy` is false. The login page sits
 * behind a dimmed overlay carrying the consent sheet:
 *  - 同意并进入 writes the flag and proceeds to login (`GuideActivity` is a separate unrecovered screen,
 *    so the rebuild goes straight to `LoginActivity`);
 *  - 不同意 only hides the overlay — it neither writes the flag nor exits;
 *  - tapping the covered login page (or a login button) afterwards re-opens the retention dialog, whose
 *    own disagree is a no-op dismiss.
 * The back key finishes the screen, as recovered (no `onBackPressed` override).
 */
class EmptyLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmptyLoginBinding

    var navigation: LoginNavigation = DefaultLoginNavigation(this)

    private val gate get() = appGraph(this).privacyGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmptyLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.colorWhite)

        renderConsentLinks()
        binding.dialogPrivacyPolicy.tvAgree.onIntervalClick { agreeAndProceed() }
        binding.dialogPrivacyPolicy.tvDisagree.onIntervalClick {
            binding.flDialogContainer.visibility = View.GONE
        }
        binding.viewTransparent.setOnClickListener { showRetentionIfHidden() }
        binding.layoutLogin.tvLoginWechat.setOnClickListener { showRetentionIfHidden() }
        binding.layoutLogin.ivLoginPhone.setOnClickListener { showRetentionIfHidden() }
        binding.layoutLogin.ivLoginQq.setOnClickListener { showRetentionIfHidden() }
    }

    private fun renderConsentLinks() {
        val spanColor = getColor(R.color.colorSpanClick)
        val agreement = getString(R.string.user_agreement_u)
        val privacy = getString(R.string.privacy_policy_u)
        var text: CharSequence = getString(R.string.dialog_privacy_policy_content)
        text = text.withClickableSpan(agreement, spanColor, bold = true) { navigation.openUserAgreement() }
        text = text.withClickableSpan(privacy, spanColor, bold = true) { navigation.openPrivacyPolicy() }
        binding.dialogPrivacyPolicy.tvContent.text = text
        binding.dialogPrivacyPolicy.tvContent.enableSpanClicks()
    }

    private fun showRetentionIfHidden() {
        if (binding.flDialogContainer.visibility == View.VISIBLE) return
        PrivacyPolicyDialog(
            context = this,
            onAgree = { agreeAndProceed() },
            onDisagree = null,
        ).apply { navigation = this@EmptyLoginActivity.navigation }.show()
    }

    private fun agreeAndProceed() {
        appGraph(this).onPrivacyAgreed()
        gate.agree()
        LoginActivity.start(this)
        finish()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, EmptyLoginActivity::class.java))
        }
    }
}