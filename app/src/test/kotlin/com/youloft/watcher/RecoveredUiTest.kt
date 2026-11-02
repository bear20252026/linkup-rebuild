package com.youloft.watcher

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Inflation is the part of "the UI runs" that can be proven without a device or the network:
 * the layouts were recovered from the APK, so if any style, attribute, drawable or custom view
 * they reference is wrong, this fails here rather than on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveredUiTest {

    private fun themedInflater(): LayoutInflater {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Watcher)
        return LayoutInflater.from(activity).cloneInContext(activity)
    }

    @Test
    fun `recovered friend request list screen inflates under the recovered theme`() {
        val root = themedInflater()
            .inflate(R.layout.activity_friend_request_list, null) as ConstraintLayout

        assertNotNull(root.findViewById<View>(R.id.layout_title))
        assertNotNull(root.findViewById<View>(R.id.iv_back))
        assertNotNull(root.findViewById<View>(R.id.iv_add_friend))
        val list = root.findViewById<RecyclerView>(R.id.recyclerView)
        assertNotNull(list)
        assertNotNull(root.findViewById<View>(R.id.emptyView))

        val header = root.findViewById<ViewGroup>(R.id.layout_title)
        val title = (0 until header.childCount)
            .map { header.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull()
        assertNotNull(title, "the recovered title bar must contain the screen title")
        assertEquals("好友申请", title.text.toString())
    }

    @Test
    fun `recovered theme resolves framework and material attributes`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Watcher)

        val out = TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.windowBackground, out, true))
        val colorPrimary = activity.resources.getIdentifier("colorPrimary", "attr", activity.packageName)
        assertTrue(colorPrimary != 0, "colorPrimary must come from the material/appcompat attrs")
    }
}
