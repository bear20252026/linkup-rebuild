package com.youloft.watcher.pages.track

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.FriendLocation
import com.youloft.linkup.api.dto.PermissionUpdateBean
import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.R
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.NetworkTrackRepository
import com.youloft.watcher.data.TrackRepository
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.databinding.ItemTrackRecordBinding
import com.youloft.watcher.pages.track.dialog.NoTrackReasonDialog
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TrackRecordFlowTest.TestApplication::class)
class TrackRecordFlowTest {
    class TestApplication : LinkUpApplication() {
        override fun onCreate() {}
        override val keyValueStores: KeyValueStores = InMemoryKeyValueStores()
        override val trackRepository: TrackRepository get() = repository
    }

    class FakeRepository : TrackRepository {
        val calls = mutableListOf<Pair<Long, String>>()
        var daysCalls = 0
        var record: suspend (String) -> ApiResponse<TrackRecordBean> = { ApiResponse(200, data = payload()) }
        var dates: suspend () -> ApiResponse<List<String>> = { ApiResponse(200, data = emptyList()) }
        override suspend fun days(userId: Long): ApiResponse<List<String>> {
            daysCalls++
            return dates()
        }
        override suspend fun record(userId: Long, date: String): ApiResponse<TrackRecordBean> {
            calls += userId to date
            return record(date)
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<TestApplication>()
    private val store = ViewModelStore()
    private var controller: ActivityController<TrackRecordActivity>? = null
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun model(): TrackRecordViewModel = TrackRecordViewModel(repository, TrackRowBuilder(TrackFormatter(app)))
        .also { store.put("track", it) }

    @Before fun before() { repository = FakeRepository() }
    @After fun after() {
        controller?.pause()?.stop()?.destroy()
        store.clear()
        idle()
    }

    @Test fun `repository forwards both read calls and returns their envelopes unchanged`() = runBlocking {
        val days = ApiResponse(200, data = listOf("2026-11-05"))
        val record = ApiResponse(403, msg = "denied", data = payload())
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = Proxy.newProxyInstance(LinkUpApi::class.java.classLoader, arrayOf(LinkUpApi::class.java)) { _, method, args ->
            calls += method.name to args.orEmpty().dropLast(1)
            when (method.name) {
                "locationDays" -> days
                "locationV2Tracks" -> record
                else -> error("Unexpected API ${method.name}")
            }
        } as LinkUpApi
        val source = NetworkTrackRepository(api)
        assertSame(days, source.days(42))
        assertSame(record, source.record(42, "2026-11-05"))
        val expected: List<Pair<String, List<Any?>>> = listOf(
            "locationDays" to listOf(42L), "locationV2Tracks" to listOf(42L, "2026-11-05"),
        )
        assertEquals(expected, calls)
    }

    @Test fun `loading publishes before suspension and success includes metadata`() {
        val deferred = CompletableDeferred<ApiResponse<TrackRecordBean>>()
        repository.record = { deferred.await() }
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.emptyVisible)
        deferred.complete(ApiResponse(200, data = payload().copy(permission = PermissionUpdateBean(), friendLocation = FriendLocation())))
        idle()
        assertFalse(vm.state.value.loading)
        assertEquals(2, vm.state.value.rows.size)
        assertNotNull(vm.state.value.permission)
        assertNotNull(vm.state.value.friendLocation)
    }

    @Test fun `null only tracks show empty state while summary remains in model`() {
        repository.record = { ApiResponse(200, data = TrackRecordBean(tracks = listOf(null))) }
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        assertTrue(vm.state.value.emptyVisible)
        assertEquals(1, vm.state.value.rows.size)
        assertTrue(vm.state.value.rows.single() is TrackRow.Summary)
    }

    @Test fun `rejected envelope ignores attached data and clears previous selection and metadata`() {
        repository.record = { ApiResponse(200, data = payload().copy(permission = PermissionUpdateBean(), friendLocation = FriendLocation())) }
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        vm.select(track())
        assertNotNull(vm.state.value.selectedTimePeriod)
        repository.record = { ApiResponse(403, msg = "没有权限", data = payload()) }
        vm.load(42, "2026-11-04")
        idle()
        assertEquals("没有权限", vm.state.value.message)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.permission)
        assertNull(vm.state.value.friendLocation)
        assertNull(vm.state.value.selectedTimePeriod)
    }

    @Test fun `transport failure exits loading and retry succeeds`() {
        repository.record = { throw IOException("offline") }
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.emptyVisible)
        assertNotNull(vm.state.value.message)
        repository.record = { ApiResponse(200, data = payload()) }
        vm.load(42, "2026-11-05")
        idle()
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.emptyVisible)
    }

    @Test fun `successful envelope without data is an error`() {
        repository.record = { ApiResponse(200) }
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        assertNotNull(vm.state.value.message)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun `late result from a non cancellable old request cannot overwrite the selected date`() {
        val old = CompletableDeferred<ApiResponse<TrackRecordBean>>()
        repository.record = { date ->
            if (date == "2026-11-04") withContext(NonCancellable) { old.await() }
            else ApiResponse(200, data = payload().copy(totalDistance = 900.0))
        }
        val vm = model()
        vm.load(42, "2026-11-04")
        idle()
        vm.load(42, "2026-11-05")
        idle()
        old.complete(ApiResponse(200, data = payload().copy(totalDistance = 200.0)))
        idle()
        assertEquals("2026-11-05", vm.state.value.selectedDate)
        assertEquals(900.0, (vm.state.value.rows.first() as TrackRow.Summary).distanceMeters)
        assertNull(vm.state.value.message)
    }

    @Test fun `late exception from old request does not replace newer success with error`() {
        val old = CompletableDeferred<Unit>()
        repository.record = { date ->
            if (date == "2026-11-04") withContext(NonCancellable) { old.await(); throw IOException("old") }
            else ApiResponse(200, data = payload())
        }
        val vm = model()
        vm.load(42, "2026-11-04")
        idle()
        vm.load(42, "2026-11-05")
        idle()
        old.complete(Unit)
        idle()
        assertFalse(vm.state.value.emptyVisible)
        assertNull(vm.state.value.message)
    }

    @Test fun `selection toggles and non selectable or absent points clear it`() {
        val vm = model()
        vm.load(42, "2026-11-05")
        idle()
        vm.select(track())
        assertEquals(100L, vm.state.value.selectedTimePeriod)
        vm.select(track())
        assertNull(vm.state.value.selectedTimePeriod)
        vm.select(track())
        vm.select(track(type = 3))
        assertNull(vm.state.value.selectedTimePeriod)
        vm.select(track())
        vm.clearSelection()
        assertNull(vm.state.value.selectedTimePeriod)
    }

    @Test fun `calendar always generates fifteen dates including leap day and days only control dots`() {
        repository.dates = { ApiResponse(200, data = listOf("2024-02-29", "2024-02-29", "2020-01-01")) }
        val vm = model()
        vm.loadDays(42, LocalDate.of(2024, 3, 1))
        idle()
        assertEquals(15, vm.state.value.dates.size)
        assertEquals(listOf("2024-03-01", "2024-02-29", "2024-02-28"), vm.state.value.dates.take(3))
        assertEquals("2024-02-16", vm.state.value.dates.last())
        assertEquals(setOf("2024-02-29", "2020-01-01"), vm.state.value.recordedDates)
    }

    @Test fun `failed days request preserves date strip and does not erase track data`() {
        repository.dates = { throw IOException("offline") }
        val vm = model()
        vm.load(42, "2026-11-05")
        vm.loadDays(42)
        idle()
        assertEquals(15, vm.state.value.dates.size)
        assertNotNull(vm.state.value.daysMessage)
        assertEquals(2, vm.state.value.rows.size)
    }

    @Test fun `days envelope rejection ignores data and retry replaces the error`() {
        repository.dates = { ApiResponse(403, msg = "拒绝", data = listOf("2026-11-05")) }
        val vm = model()
        vm.loadDays(42)
        idle()
        assertTrue(vm.state.value.recordedDates.isEmpty())
        assertEquals("拒绝", vm.state.value.daysMessage)
        repository.dates = { ApiResponse(200, data = listOf("2026-11-05")) }
        vm.loadDays(42)
        idle()
        assertNull(vm.state.value.daysMessage)
        assertEquals(setOf("2026-11-05"), vm.state.value.recordedDates)
    }

    private fun launch(id: Long = 42): TrackRecordActivity {
        val intent = Intent(app, TrackRecordActivity::class.java).putExtra(TrackRecordActivity.EXTRA_QUERY_ID, id)
        controller = Robolectric.buildActivity(TrackRecordActivity::class.java, intent).setup()
        idle()
        return controller!!.get()
    }
    private fun model(activity: TrackRecordActivity) = ViewModelProvider(activity)[TrackRecordViewModel::class.java]
    private fun list(activity: TrackRecordActivity) = activity.findViewById<RecyclerView>(R.id.recyclerView)
    private fun measure(activity: TrackRecordActivity) {
        list(activity).measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        list(activity).layout(0, 0, 1080, 1800)
    }

    @Test fun `host displays fifteen tabs with recorded dots and clicks request the selected date`() {
        val today = LocalDate.now()
        repository.dates = { ApiResponse(200, data = listOf(today.minusDays(1).toString())) }
        val activity = launch()
        val tabs = activity.findViewById<LinearLayout>(R.id.track_dates)
        assertEquals(15, tabs.childCount)
        assertEquals(View.INVISIBLE, tabs.getChildAt(0).findViewById<View>(R.id.view_dot).visibility)
        assertEquals(View.VISIBLE, tabs.getChildAt(1).findViewById<View>(R.id.view_dot).visibility)
        tabs.getChildAt(1).performClick()
        idle()
        assertEquals(42L to today.minusDays(1).toString(), repository.calls.last())
        assertTrue(tabs.getChildAt(1).isSelected)
        assertFalse(tabs.getChildAt(0).isSelected)
        assertEquals(2, (list(activity).adapter as ConcatAdapter).adapters.size)
    }

    @Test fun `date labels and circular backgrounds match original instead of blue month day text`() {
        val activity = launch()
        val tabs = activity.findViewById<LinearLayout>(R.id.track_dates)
        val today = LocalDate.now()
        val selectedDate = tabs.getChildAt(0).findViewById<TextView>(R.id.tv_date)
        assertEquals(today.dayOfMonth.toString(), selectedDate.text.toString())
        assertEquals(activity.getColor(R.color.appPrimary), selectedDate.currentTextColor)
        assertEquals(
            activity.getColor(R.color.colorBrand),
            (selectedDate.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor,
        )
        assertEquals(
            arrayOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1],
            tabs.getChildAt(0).findViewById<TextView>(R.id.tv_week).text.toString(),
        )
        tabs.getChildAt(1).performClick()
        idle()
        assertEquals(
            android.graphics.Color.parseColor("#0f000000"),
            (selectedDate.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor,
        )
        assertNull(tabs.getChildAt(2).findViewById<TextView>(R.id.tv_date).background)
    }

    private fun layoutScreen(activity: TrackRecordActivity, width: Int = 400, height: Int = 800) {
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        root.layoutParams = root.layoutParams.apply { this.width = width; this.height = height }
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        }
    }

    @Test @Config(qualifiers = "mdpi")
    fun `sheet starts at original 383dp height and clips the 32dp rounded header`() {
        val activity = launch()
        layoutScreen(activity)
        val sheet = activity.findViewById<View>(R.id.track_sheet)
        val parent = activity.findViewById<View>(R.id.track_coordinator)
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED, behavior.state)
        assertEquals(123, behavior.peekHeight)
        assertFalse(behavior.isHideable)
        assertFalse(behavior.isFitToContents)
        fun offset(name: String) = com.google.android.material.bottomsheet.BottomSheetBehavior::class.java.getDeclaredField(name).apply { isAccessible = true }.get(behavior)
        assertTrue(kotlin.math.abs(parent.height - sheet.top - 383) <= 1,
            "parent=${parent.height}, sheetTop=${sheet.top}, sheetH=${sheet.height}, ratio=${behavior.halfExpandedRatio}, half=${offset("halfExpandedOffset")}, collapsed=${offset("collapsedOffset")}, expanded=${offset("expandedOffset")}, fit=${offset("fitToContentsOffset")}, state=${behavior.state}")
        assertTrue(sheet.clipToOutline)
        val outline = android.graphics.Outline()
        sheet.outlineProvider.getOutline(sheet, outline)
        assertEquals(32f, outline.radius)
        assertEquals(0f, activity.findViewById<View>(R.id.track_title).alpha, 0.01f)
        assertEquals(1f, activity.findViewById<View>(R.id.track_tab_background).alpha, 0.01f)
        assertTrue(activity.findViewById<View>(R.id.track_map_container).height > 383)
    }

    @Test @Config(qualifiers = "mdpi")
    fun `expanded and collapsed states move the panel and crossfade the original chrome`() {
        val activity = launch()
        layoutScreen(activity)
        val sheet = activity.findViewById<View>(R.id.track_sheet)
        val parent = activity.findViewById<View>(R.id.track_coordinator)
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        layoutScreen(activity)
        assertEquals(0, sheet.top)
        assertEquals(1f, activity.findViewById<View>(R.id.track_title).alpha)
        assertEquals(1f, activity.findViewById<View>(R.id.track_map_mask).alpha)
        assertEquals(0f, activity.findViewById<View>(R.id.track_tab_background).alpha)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.track_reset_card).visibility)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        layoutScreen(activity)
        assertEquals(123, parent.height - sheet.top)
        assertEquals(0f, activity.findViewById<View>(R.id.track_title).alpha)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.track_reset_card).visibility)
        assertEquals((sheet.top - 52).toFloat(), activity.findViewById<View>(R.id.track_reset_card).translationY)
    }

    @Test @Config(qualifiers = "mdpi")
    fun `small windows expand rather than leaving a panel above the screen`() {
        val activity = launch()
        layoutScreen(activity, height = 350)
        val sheet = activity.findViewById<View>(R.id.track_sheet)
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED, behavior.state)
        assertEquals(0, sheet.top)
        assertEquals(306, sheet.height)
    }

    @Test @Config(qualifiers = "mdpi")
    fun `system bars and side cutouts do not cover title or sheet content`() {
        val activity = launch()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
            androidx.core.view.WindowInsetsCompat.Builder().setInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(8, 24, 12, 32),
            ).build(),
        )
        layoutScreen(activity)
        assertEquals(24, root.paddingTop)
        assertEquals(32, root.paddingBottom)
        assertEquals(8, root.paddingLeft)
        assertEquals(12, root.paddingRight)
        assertEquals(380, activity.findViewById<View>(R.id.track_sheet).width)
        assertEquals(700, activity.findViewById<View>(R.id.track_coordinator).height)
    }

    @Test fun `host loading and empty overlay follow request completion`() {
        val response = CompletableDeferred<ApiResponse<TrackRecordBean>>()
        repository.record = { response.await() }
        val activity = launch()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.track_loading).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyLayout).visibility)
        response.complete(ApiResponse(200, data = TrackRecordBean()))
        idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.track_loading).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyLayout).visibility)
        assertEquals(View.GONE, list(activity).visibility)
    }

    @Test fun `host displays error and refresh button retries both reads`() {
        repository.record = { throw IOException("offline") }
        val activity = launch()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.track_message).visibility)
        repository.record = { ApiResponse(200, data = payload()) }
        activity.findViewById<View>(R.id.track_refresh).performClick()
        idle()
        assertEquals(2, repository.calls.size)
        assertEquals(2, repository.daysCalls)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.track_message).visibility)
        assertEquals(View.VISIBLE, list(activity).visibility)
    }

    @Test fun `host refuses missing account without making read calls`() {
        val activity = launch(0)
        assertTrue(repository.calls.isEmpty())
        assertEquals(0, repository.daysCalls)
        assertFalse(activity.findViewById<View>(R.id.track_refresh).isEnabled)
    }

    @Test fun `row click selects and clicking again clears with actual view rebind`() {
        val activity = launch()
        measure(activity)
        val row = list(activity).findViewHolderForAdapterPosition(1)!!.itemView
        row.performClick()
        idle()
        measure(activity)
        assertEquals(100L, model(activity).state.value.selectedTimePeriod)
        assertTrue(list(activity).findViewHolderForAdapterPosition(1)!!.itemView.isSelected)
        row.performClick()
        idle()
        measure(activity)
        assertNull(model(activity).state.value.selectedTimePeriod)
        assertFalse(list(activity).findViewHolderForAdapterPosition(1)!!.itemView.isSelected)
    }

    @Test fun `warning bubble opens real dialog and confirm dismisses it`() {
        repository.record = { ApiResponse(200, data = payload(track(type = 4))) }
        val activity = launch()
        measure(activity)
        val row = list(activity).findViewHolderForAdapterPosition(1)!!.itemView
        row.findViewById<View>(R.id.tv_line_type_content).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is NoTrackReasonDialog)
        assertTrue(dialog.isShowing)
        assertEquals(android.view.Gravity.BOTTOM, dialog.window!!.attributes.gravity)
        dialog.findViewById<View>(R.id.tv_ok).performClick()
        assertFalse(dialog.isShowing)
    }

    @Test fun `destroying screen dismisses open reason dialog`() {
        repository.record = { ApiResponse(200, data = payload(track(type = 4))) }
        val activity = launch()
        measure(activity)
        list(activity).findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.tv_line_type_content).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        controller!!.pause().stop().destroy()
        controller = null
        assertFalse(dialog.isShowing)
    }

    @Test fun `recycled warning row clears arrow tint listener and address before stay and unknown rows`() {
        val activity = launch()
        val adapter = TrackPointAdapter(mutableListOf(), {}, {}, {})
        val builder = TrackRowBuilder(TrackFormatter(activity))
        adapter.submitList(listOf(TrackRow.Point(builder.row(track(type = 4)))))
        val holder = adapter.createViewHolder(LinearLayout(activity), adapter.getItemViewType(0))
        fun bind(track: TrackRecordBean.Track) {
            adapter.submitList(listOf(TrackRow.Point(builder.row(track))))
            adapter.bindViewHolder(holder, 0)
        }
        bind(track(type = 4))
        val binding = ItemTrackRecordBinding.bind(holder.itemView)
        assertTrue(binding.tvLineTypeContent.isClickable)
        assertNotNull(binding.tvLineTypeContent.compoundDrawablesRelative[2])
        bind(track(type = 1))
        assertFalse(binding.tvLineTypeContent.isClickable)
        assertTrue(binding.tvLineTypeContent.compoundDrawablesRelative.all { it == null })
        assertNull(binding.tvLineTypeContent.compoundDrawableTintList)
        assertEquals(View.VISIBLE, binding.arriveLayout.visibility)
        bind(track(type = 5))
        assertEquals(View.GONE, binding.arriveLayout.visibility)
        assertEquals("", binding.tvAddress.text.toString())
        bind(track().copy(line = null))
        assertEquals(View.GONE, binding.timeLine.visibility)
        assertEquals(View.GONE, binding.tvLineTypeContent.visibility)
        assertFalse(binding.tvLineTypeContent.isClickable)
        bind(track().copy(lostType = 99))
        assertEquals(View.GONE, binding.arriveLayout.visibility)
        assertEquals(View.GONE, binding.tvPointType.visibility)
        assertEquals("", binding.tvAddress.text.toString())
    }

    @Test fun `selected move loses background and icon tints when recycled into a warning`() {
        val activity = launch()
        val builder = TrackRowBuilder(TrackFormatter(activity))
        val adapter = TrackPointAdapter(mutableListOf(TrackRow.Point(builder.row(track(type = 2)))), {}, {}, {})
        adapter.selectedTimePeriod = 100
        val holder = adapter.createViewHolder(LinearLayout(activity), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val binding = ItemTrackRecordBinding.bind(holder.itemView)
        assertNotNull(binding.tvLineTypeContent.backgroundTintList)
        assertEquals(android.graphics.Color.WHITE, binding.tvLineTypeContent.compoundDrawableTintList!!.defaultColor)
        adapter.selectedTimePeriod = null
        adapter.submitList(listOf(TrackRow.Point(builder.row(track(type = 3)))))
        adapter.bindViewHolder(holder, 0)
        assertFalse(binding.root.isSelected)
        assertNull(binding.tvLineTypeContent.backgroundTintList)
        assertNull(binding.tvLineTypeContent.compoundDrawableTintList)
        assertTrue(binding.tvLineTypeContent.compoundDrawablesRelative.all { it == null })
        assertEquals(android.graphics.Color.parseColor("#FF7A7A"), binding.tvLineTypeContent.currentTextColor)
        assertFalse(binding.tvLineTypeContent.isClickable)
    }

    @Test fun `rotation retains selected date and does not refetch already loaded records`() {
        val activity = launch()
        activity.findViewById<LinearLayout>(R.id.track_dates).getChildAt(2).performClick()
        idle()
        val date = model(activity).state.value.selectedDate
        val count = repository.calls.size
        controller!!.configurationChange()
        idle()
        assertEquals(date, model(controller!!.get()).state.value.selectedDate)
        assertEquals(count, repository.calls.size)
    }

    companion object {
        private var repository = FakeRepository()
        private fun track(type: Int = 1) = TrackRecordBean.Track(
            address = "测试地点", timePeriod = 100, type = 2,
            line = TrackRecordBean.Line(type = type, duration = 60, distance = 500.0),
        )
        private fun payload(point: TrackRecordBean.Track = track()) = TrackRecordBean(tracks = listOf(point))
    }
}
