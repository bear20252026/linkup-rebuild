package com.youloft.watcher.pages.login.dialog

import android.content.Context
import android.text.SpannableString
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.R
import com.youloft.watcher.databinding.DialogPolicyRemindBinding
import com.youloft.watcher.ext.enableSpanClicks
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.ext.withClickableSpan
import com.youloft.watcher.pages.login.DefaultLoginNavigation
import com.youloft.watcher.pages.login.LoginNavigation

/**
 * Recovered `pages.login.dialog.PolicyRemindDialog` — the sheet that interrupts a login when the
 * agreement checkbox is still off. It is the *only* gate in front of every login button, and confirming
 * it does not just continue: the caller also ticks the checkbox for the user.
 *
 * `hidePlatformPolicies` is the recovered fifth argument (1.6.6 `f64838d`): when true the two in-app
 * policies drop out of the sentence and only the carrier's own terms are named. `LoginActivity` always
 * passes false; the number-auth path is what uses the other form.
 *
 * The two nav links and the carrier link all open `CommonWebActivity`, which is its own card, so they are
 * recorded rather than launched.
 */
class PolicyRemindDialog(
    context: Context,
    private val carrierName: String = "",
    private val carrierUrl: String = "",
    private val hidePlatformPolicies: Boolean = false,
    private val onConfirm: () -> Unit,
) : BindingDialog<DialogPolicyRemindBinding>(context) {

    var navigation: LoginNavigation = DefaultLoginNavigation(context)

    val privacyLabel: String by lazy { context.getString(R.string.privacy_policy_u) }

    val agreementLabel: String by lazy { context.getString(R.string.user_agreement_u) }

    /** 《carrierName》, or empty when the SDK gave no carrier terms — the original guarded the same way. */
    val carrierLabel: String by lazy {
        if (carrierName.isNotEmpty()) context.getString(R.string.service_terms_text, carrierName) else ""
    }

    override fun onInit(binding: DialogPolicyRemindBinding) {
        binding.tvContent.text = contentText()
        binding.tvContent.enableSpanClicks()

        binding.tvCancel.onIntervalClick { dismissDelayed() }
        binding.tvConfirm.onIntervalClick {
            onConfirm()
            dismissDelayed()
        }
    }

    /**
     * `dialog_read_remind_above(privacy + user + 和 + carrier)`, with each named document turned into a
     * clickable span. The joiner only appears when there is a carrier document *and* the platform
     * policies are still in the sentence — the recovered `if (!hide && carrier.isNotEmpty())` clause.
     */
    internal fun contentText(): CharSequence {
        val spanColor = context.getColor(R.color.colorSpanClick)
        val privacy = if (!hidePlatformPolicies) privacyLabel else ""
        val agreement = if (!hidePlatformPolicies) agreementLabel else ""
        val joiner = if (!hidePlatformPolicies && carrierLabel.isNotEmpty()) context.getString(R.string.and) else ""
        val sentence = context.getString(R.string.dialog_read_remind_above, privacy + agreement + joiner + carrierLabel)

        var text: CharSequence = SpannableString(sentence)
        if (privacy.isNotEmpty()) {
            text = text.withClickableSpan(privacy, spanColor, bold = true) { navigation.openPrivacyPolicy() }
        }
        if (agreement.isNotEmpty()) {
            text = text.withClickableSpan(agreement, spanColor, bold = true) { navigation.openUserAgreement() }
        }
        if (carrierLabel.isNotEmpty()) {
            text = text.withClickableSpan(carrierLabel, spanColor, bold = true) {
                navigation.openCarrierPolicy(carrierUrl, carrierName)
            }
        }
        return text
    }

    companion object {
        /** What the original reported: `ui_launch` on show, `ui_click` on the confirm button. */
        const val ENTER_LABEL = "请阅读协议弹窗"
        const val CONFIRM_LABEL = "已阅读并同意"
    }
}
