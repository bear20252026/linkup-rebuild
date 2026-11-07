package com.youloft.watcher.pages.login

import android.app.Activity
import android.content.Context

/**
 * Where the login screen hands control next. Two of these destinations are rebuilt screens that launch
 * for real, the rest are separate cards and stay recorded:
 *
 *  - `ImproveUserInfoActivity` (the profile-completion screen a first-ever login is routed to) and
 *    `MainActivity` (the tab host) are their own cards;
 *  - `CommonWebActivity` is the in-app browser every agreement link opens.
 *
 * The recovered call sites passed the H5 hand-off pair (`from_h5`, `match_code`) straight through the
 * login screen into the profile screen, so both are kept here even while nothing reads them.
 */
interface LoginNavigation {
    /** `ImproveUserInfoActivity.a(context, avatar, nickname, fromH5, matchCode)`. */
    fun openImproveUserInfo(avatar: String, nickname: String, fromH5: Boolean, matchCode: Long)

    /** `MainActivity.a.c(context, tab = null, source = 0)`. */
    fun openMain()

    /** `CommonWebActivity.A` / `.B` — the privacy policy. */
    fun openPrivacyPolicy()

    /** `CommonWebActivity.C` / `.D` — the user agreement. */
    fun openUserAgreement()

    /** `CommonWebActivity.n(context, url, title)` — the carrier's own privacy terms. */
    fun openCarrierPolicy(url: String, title: String)
}

/** `ImproveUserInfoActivity` and `MainActivity` are still their own cards; the web links now open for real. */
class DefaultLoginNavigation(
    protected val context: Context,
) : LoginNavigation {

    override fun openImproveUserInfo(avatar: String, nickname: String, fromH5: Boolean, matchCode: Long) = Unit

    override fun openMain() = Unit

    override fun openPrivacyPolicy() =
        com.youloft.watcher.pages.web.CommonWebActivity.start(
            context, com.youloft.watcher.pages.web.CommonWebActivity.PRIVACY_URL,
            context.getString(com.youloft.watcher.R.string.privacy_policy_u), fullscreen = true,
        )

    override fun openUserAgreement() =
        com.youloft.watcher.pages.web.CommonWebActivity.start(
            context, com.youloft.watcher.pages.web.CommonWebActivity.USER_AGREEMENT_URL,
            context.getString(com.youloft.watcher.R.string.user_agreement_u), fullscreen = true,
        )

    override fun openCarrierPolicy(url: String, title: String) {
        if (com.youloft.watcher.pages.web.CommonWebActivity.isWebUrl(url)) {
            com.youloft.watcher.pages.web.CommonWebActivity.start(context, url, title)
        }
    }
}
