package com.youloft.watcher.pages.login.dialog

import android.content.Context
import androidx.annotation.StringRes
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.R
import com.youloft.watcher.databinding.DialogPrivacyPolicyBinding
import com.youloft.watcher.ext.enableSpanClicks
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.ext.withClickableSpan
import com.youloft.watcher.pages.login.DefaultLoginNavigation
import com.youloft.watcher.pages.login.LoginNavigation

/**
 * Recovered `pages.login.dialog.PrivacyPolicyDialog` — the "欢迎使用LinkUp" consent sheet. The first-launch
 * gate shows the same layout inline inside `EmptyLoginActivity`; this dialog is the retention popup that
 * re-appears when a user who dismissed the gate taps a login button again.
 *
 * The two named documents open the web card; agreeing and disagreeing are caller decisions (the gate
 * writes the consent flag and proceeds, the retention popup's disagree is a no-op dismiss), so they are
 * callbacks rather than behaviour baked in here. Back and outside-touch are disabled, as recovered.
 */
class PrivacyPolicyDialog(
    context: Context,
    private val onAgree: () -> Unit,
    private val onDisagree: (() -> Unit)? = null,
    @StringRes private val contentRes: Int = R.string.dialog_privacy_policy_content,
) : BindingDialog<DialogPrivacyPolicyBinding>(context) {

    var navigation: LoginNavigation = DefaultLoginNavigation(context)

    override fun onInit(binding: DialogPrivacyPolicyBinding) {
        setCanceledOnTouchOutside(false)
        setCancelable(false)

        val spanColor = context.getColor(R.color.colorSpanClick)
        val agreement = context.getString(R.string.user_agreement_u)
        val privacy = context.getString(R.string.privacy_policy_u)
        var text: CharSequence = context.getString(contentRes)
        text = text.withClickableSpan(agreement, spanColor, bold = true) { navigation.openUserAgreement() }
        text = text.withClickableSpan(privacy, spanColor, bold = true) { navigation.openPrivacyPolicy() }
        binding.tvContent.text = text
        binding.tvContent.enableSpanClicks()

        binding.tvAgree.onIntervalClick {
            onAgree()
            dismissDelayed()
        }
        binding.tvDisagree.onIntervalClick {
            onDisagree?.invoke()
            dismissDelayed()
        }
    }
}