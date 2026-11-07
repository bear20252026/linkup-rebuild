package com.youloft.watcher.pages.login

import android.content.Context
import android.os.Looper
import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.R
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.PrivacyGate
import com.youloft.watcher.pages.login.LoginFixtures.RecordingLoginNavigation
import com.youloft.watcher.pages.login.LoginFixtures.TestApplication
import com.youloft.watcher.pages.login.dialog.PrivacyPolicyDialog
import com.youloft.watcher.pages.web.CommonWebActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class PrivacyGateFlowTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val stores get() = LoginFixtures.stores
    private val navigation = RecordingLoginNavigation()

    @Before fun setUp() {
        LoginFixtures.reset()
        navigation.calls.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `consent flag defaults false and only an explicit agree writes it`() {
        val gate = PrivacyGate(stores)
        assertFalse(gate.agreed())
        gate.agree()
        assertTrue(gate.agreed())
        assertTrue(stores.shared.boolean(PrivacyGate.KEY_AGREE_PRIVACY_POLICY))
    }

    private fun launchEmpty(): EmptyLoginActivity {
        val controller = Robolectric.buildActivity(EmptyLoginActivity::class.java)
        controller.get().navigation = navigation
        controller.setup()
        idle()
        return controller.get()
    }

    private fun <T : View> view(activity: EmptyLoginActivity, id: Int): T = activity.findViewById(id)

    @Test fun `agreeing writes the flag, opens login and finishes the gate`() {
        val activity = launchEmpty()
        view<View>(activity, R.id.tv_agree).performClick()
        idle()
        assertTrue(PrivacyGate(stores).agreed())
        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(LoginActivity::class.java.name, next.component?.className)
    }

    @Test fun `first disagree only hides the overlay without writing the flag or exiting`() {
        val activity = launchEmpty()
        view<View>(activity, R.id.tv_disagree).performClick()
        idle()
        assertEquals(View.GONE, view<View>(activity, R.id.fl_dialog_container).visibility)
        assertFalse(PrivacyGate(stores).agreed())
        assertFalse(activity.isFinishing)
    }

    @Test fun `tapping the covered login page reopens a retention dialog whose disagree is a no-op`() {
        val activity = launchEmpty()
        view<View>(activity, R.id.tv_disagree).performClick()
        idle()
        view<View>(activity, R.id.view_transparent).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is PrivacyPolicyDialog)
        assertTrue(dialog.isShowing)
        dialog.findViewById<View>(R.id.tv_disagree).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertFalse(PrivacyGate(stores).agreed())
        assertFalse(activity.isFinishing)
    }

    @Test fun `retention dialog agree proceeds exactly like the gate`() {
        val activity = launchEmpty()
        view<View>(activity, R.id.tv_disagree).performClick()
        idle()
        view<View>(activity, R.id.iv_login_phone).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as PrivacyPolicyDialog
        dialog.findViewById<View>(R.id.tv_agree).performClick()
        idle()
        assertTrue(PrivacyGate(stores).agreed())
        assertTrue(activity.isFinishing)
    }

    @Test fun `consent content links open the two web documents`() {
        val activity = launchEmpty()
        val text = view<TextView>(activity, R.id.tv_content).text as Spannable
        val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(2, spans.size)
        val agreement = context.getString(R.string.user_agreement_u)
        val privacy = context.getString(R.string.privacy_policy_u)
        spans.first { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() == agreement }
            .onClick(activity.findViewById(R.id.tv_content))
        spans.first { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() == privacy }
            .onClick(activity.findViewById(R.id.tv_content))
        assertEquals(listOf("agreement", "privacy"), navigation.calls)
    }

    @Test fun `retention dialog routes its own spans and fires the agree callback`() {
        var agreed = false
        val dialog = PrivacyPolicyDialog(context, onAgree = { agreed = true })
        dialog.navigation = navigation
        dialog.show()
        idle()
        val content = dialog.findViewById<TextView>(R.id.tv_content)
        val text = content.text as Spannable
        val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(2, spans.size)
        spans.forEach { it.onClick(content) }
        assertEquals(2, navigation.calls.size)
        dialog.findViewById<View>(R.id.tv_agree).performClick()
        idle()
        assertTrue(agreed)
        assertFalse(dialog.isShowing)
    }

    private fun hostActivity(): android.app.Activity =
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

    @Test fun `login navigation opens the two web documents fullscreen`() {
        val host = hostActivity()
        val nav = DefaultLoginNavigation(host)
        nav.openPrivacyPolicy()
        val privacy = shadowOf(host).nextStartedActivity
        assertEquals(CommonWebActivity::class.java.name, privacy.component?.className)
        assertEquals(CommonWebActivity.PRIVACY_URL, privacy.getStringExtra(CommonWebActivity.EXTRA_URL))
        assertTrue(privacy.getBooleanExtra(CommonWebActivity.EXTRA_FULLSCREEN, false))
        nav.openUserAgreement()
        val agreement = shadowOf(host).nextStartedActivity
        assertEquals(CommonWebActivity.USER_AGREEMENT_URL, agreement.getStringExtra(CommonWebActivity.EXTRA_URL))
    }

    @Test fun `carrier policy opens only for web schemes`() {
        val host = hostActivity()
        val nav = DefaultLoginNavigation(host)
        nav.openCarrierPolicy("https://carrier.example/terms", "运营商条款")
        assertEquals("https://carrier.example/terms", shadowOf(host).nextStartedActivity.getStringExtra(CommonWebActivity.EXTRA_URL))
        nav.openCarrierPolicy("file:///etc/passwd", "bad")
        assertNull(shadowOf(host).nextStartedActivity)
        assertFalse(CommonWebActivity.isWebUrl("javascript:alert(1)"))
        assertFalse(CommonWebActivity.isWebUrl("file:///x"))
        assertTrue(CommonWebActivity.isWebUrl("https://web-link.tttick.com/x"))
    }
}
