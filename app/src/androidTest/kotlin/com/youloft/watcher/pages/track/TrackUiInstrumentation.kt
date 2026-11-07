package com.youloft.watcher.pages.track

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.TrackRepository
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.pages.track.dialog.NoTrackReasonDialog
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Native runner; all fixtures live in the test APK and never instantiate a network repository. */
class TrackUiInstrumentation : Instrumentation() {
    class TestApp : LinkUpApplication() {
        override val keyValueStores = InMemoryKeyValueStores()
        override val trackRepository = OfflineRepository()
        override fun onCreate() {} // No production startup side effects.
    }

    class OfflineRepository : TrackRepository {
        val today: LocalDate = LocalDate.now()
        val dates = listOf(today.toString(), today.minusDays(1).toString())
        @Volatile var mode = "normal"
        var records = 0
        var dayReads = 0
        var lastCall: Pair<Long, String>? = null
        override suspend fun days(userId: Long): ApiResponse<List<String>> {
            check(userId == 42L) { "days: expected queryId=42, got $userId" }
            dayReads++
            return ApiResponse(200, data = dates)
        }
        override suspend fun record(userId: Long, date: String): ApiResponse<TrackRecordBean> {
            check(userId == 42L) { "record: expected queryId=42, got $userId" }
            records++
            lastCall = userId to date
            if (mode == "error") return ApiResponse(503, msg = "离线测试：轨迹加载失败")
            val noon = LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val points = listOf(1, 2, 4, 5).mapIndexed { index, kind ->
                TrackRecordBean.Track(
                    address = "离线测试地点 ${index + 1}", timePeriod = noon - index * 3_600_000L,
                    lat = 29.56 + index * 0.001, lng = 106.55 + index * 0.001,
                    type = if (kind == 1) 2 else 3, lostType = if (kind == 4) 1 else 0,
                    line = TrackRecordBean.Line(type = kind, duration = 1800, distance = if (kind == 2) 1500.0 else 0.0),
                )
            }
            return ApiResponse(200, data = if (mode == "empty") TrackRecordBean(tracks = emptyList())
                else TrackRecordBean(userId = userId, tracks = points, arrivalPointCount = 1,
                    totalDistance = 1500.0, totalStayTime = 1800))
        }
    }

    private lateinit var activity: TrackRecordActivity
    private lateinit var model: TrackRecordViewModel
    private lateinit var repo: OfflineRepository
    private val passed = arrayListOf<String>()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow() // Propagate UI assertions to onStart, not the main looper.
    }
    private fun view(id: Int): View = requireNotNull(activity.findViewById<View>(id)) { "Missing view ID $id" }
    private fun list() = view(R.id.recyclerView) as RecyclerView
    private fun sheet() = BottomSheetBehavior.from(view(R.id.track_sheet))
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            if (main(condition)) { waitForIdleSync(); return }
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timeout (5s): $label; " + main {
            "state=${model.state.value}, sheet=${sheet().state}, top=${view(R.id.track_sheet).top}, parent=${view(R.id.track_coordinator).height}"
        })
    }
    private fun report(): Bundle = Bundle().apply {
        putInt("passed", passed.size)
        putStringArrayList("items", ArrayList(passed))
        putString("stream", passed.joinToString("\n", postfix = "\n"))
    }
    private fun pass(item: String) { passed += item; sendStatus(0, report()) }
    private fun screenshot(stage: String) {
        waitForIdleSync()
        val bitmap = checkNotNull(uiAutomation.takeScreenshot()) { "Screenshot failed: $stage" }
        val file = File(checkNotNull(targetContext.getExternalFilesDir(null)), "track-ui-$stage.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG failed: $file" } } }
        finally { bitmap.recycle() }
        pass("screenshot/$stage: ${file.absolutePath}")
    }
    private fun click(id: Int) = main { check(view(id).isShown && view(id).performClick()) { "Cannot click ID $id" } }
    private fun row(position: Int): View = checkNotNull(list().findViewHolderForAdapterPosition(position)) {
        "No attached row at $position"
    }.itemView
    private fun showRow(position: Int) {
        main { list().scrollToPosition(position) }
        await("row $position attached") { list().findViewHolderForAdapterPosition(position)?.itemView?.isShown == true }
    }
    private fun dragUp() {
        val path = main {
            val start = IntArray(2); view(R.id.track_sheet).getLocationOnScreen(start)
            val end = IntArray(2); view(R.id.track_coordinator).getLocationOnScreen(end)
            floatArrayOf(start[0] + view(R.id.track_sheet).width / 2f, start[1] + dp(8).toFloat(), end[1] + dp(2).toFloat())
        }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, path[0], y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            try { sendPointerSync(e) } finally { e.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, path[1])
        for (step in 1..20) {
            SystemClock.sleep(20)
            event(MotionEvent.ACTION_MOVE, path[1] + (path[2] - path[1]) * step / 20f)
        }
        event(MotionEvent.ACTION_UP, path[2])
        waitForIdleSync()
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        Instrumentation.newApplication(TestApp::class.java, context)
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        super.onStart() // Instrumentation.start() runs this on its worker thread.
        try {
            activity = startActivitySync(Intent(targetContext, TrackRecordActivity::class.java)
                .putExtra(TrackRecordActivity.EXTRA_QUERY_ID, 42L).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TrackRecordActivity
            main {
                repo = (activity.application as TestApp).trackRepository
                model = ViewModelProvider(activity)[TrackRecordViewModel::class.java]
            }
            await("initial normal data and 383dp half sheet (portrait window must exceed 383dp)") {
                !model.state.value.loading && model.state.value.rows.size == 5 && list().isShown &&
                    sheet().state == BottomSheetBehavior.STATE_HALF_EXPANDED &&
                    abs(view(R.id.track_coordinator).height - view(R.id.track_sheet).top - dp(383)) <= 1
            }
            main {
                val state = model.state.value
                check(state.recordedDates == repo.dates.toSet()) { "Recorded dates differ from today/yesterday" }
                check(state.rows.filterIsInstance<TrackRow.Point>().map { it.view.line?.kind } ==
                    listOf(LineKind.STAY, LineKind.MOVE, LineKind.NO_NEW_LOCATION, LineKind.UNKNOWN)) { "Wrong point order/types" }
                check((state.rows.first() as TrackRow.Summary).let { it.stayCount == 1 && it.distanceMeters == 1500.0 }) { "Wrong summary" }
            }
            pass("initial: 383dp ±1px, four ordered offline points, summary and recorded dates"); screenshot("half")
            main { check((view(R.id.track_dates) as ViewGroup).getChildAt(1).performClick()) { "Date click failed" } }
            await("yesterday click updates model and repository") {
                model.state.value.selectedDate == repo.dates[1] && !model.state.value.loading &&
                    repo.lastCall == (42L to repo.dates[1]) && (view(R.id.track_dates) as ViewGroup).getChildAt(1).isSelected
            }
            pass("date: yesterday selected through tab click")
            dragUp()
            await("real pointer drag expands sheet") {
                sheet().state == BottomSheetBehavior.STATE_EXPANDED && abs(view(R.id.track_sheet).top) <= 1 &&
                    view(R.id.track_title).alpha >= 0.99f && view(R.id.track_map_mask).alpha >= 0.99f
            }
            pass("expanded: sendPointerSync drag and chrome crossfade"); screenshot("expanded")
            showRow(1)
            main { check(row(1).performClick()) { "Selection click failed" } }
            await("row selected and rebound") { model.state.value.selectedTimePeriod ==
                (model.state.value.rows[1] as TrackRow.Point).view.track.timePeriod && row(1).isSelected }
            main { check(row(1).performClick()) { "Clear selection click failed" } }
            await("row deselected") { model.state.value.selectedTimePeriod == null && !row(1).isSelected }
            pass("list: row click selects, rebinds and toggles off")
            showRow(3)
            main { check(row(3).findViewById<View>(R.id.tv_line_type_content).performClick()) { "Warning bubble click failed" } }
            val dialog = main {
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.track_list_container) as TrackRecordFragment
                val field = TrackRecordFragment::class.java.getDeclaredField("reasonDialog").apply { isAccessible = true }
                checkNotNull(field.get(fragment) as? NoTrackReasonDialog) { "NoTrackReasonDialog was not opened by row" }
            }
            await("real dialog shown at screen bottom") {
                val window = checkNotNull(dialog.window)
                val decor = window.decorView
                val xy = IntArray(2); decor.getLocationOnScreen(xy)
                val frame = Rect(); decor.getWindowVisibleDisplayFrame(frame)
                dialog.isShowing && decor.hasWindowFocus() && decor.height > 0 &&
                    window.attributes.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM &&
                    abs(xy[1] + decor.height - frame.bottom) <= dp(2) && dialog.findViewById<View>(R.id.tv_ok).isShown
            }
            pass("dialog: actual NoTrackReasonDialog shown, bottom gravity and measured bottom verified"); screenshot("dialog")
            main { check(dialog.findViewById<View>(R.id.tv_ok).performClick()) { "Dialog confirm click failed" } }
            await("dialog confirm dismisses window") { !dialog.isShowing }
            pass("dialog: confirm closes window")
            main { sheet().state = BottomSheetBehavior.STATE_COLLAPSED }
            await("collapsed 123dp and map chrome restored") {
                sheet().state == BottomSheetBehavior.STATE_COLLAPSED &&
                    abs(view(R.id.track_coordinator).height - view(R.id.track_sheet).top - dp(123)) <= 1 &&
                    view(R.id.track_title).alpha <= 0.01f && view(R.id.track_reset_card).isShown
            }
            pass("collapsed: 123dp and map chrome restored"); screenshot("collapsed")
            main { sheet().state = BottomSheetBehavior.STATE_EXPANDED }
            await("expand for error/empty screenshots") { sheet().state == BottomSheetBehavior.STATE_EXPANDED }
            repo.mode = "error"; click(R.id.track_refresh)
            await("offline error rendered") { !model.state.value.loading && view(R.id.emptyLayout).isShown &&
                (view(R.id.track_message) as TextView).text.toString() == "离线测试：轨迹加载失败" && !list().isShown }
            pass("error: offline rejection displayed"); screenshot("error")
            val counts = main { repo.records to repo.dayReads }
            repo.mode = "normal"; click(R.id.track_refresh)
            await("refresh recovers error and repeats both reads") { !model.state.value.loading && list().isShown &&
                model.state.value.rows.size == 5 && !view(R.id.track_message).isShown &&
                repo.records == counts.first + 1 && repo.dayReads == counts.second + 1 }
            pass("refresh: error recovers via both offline repository reads")
            repo.mode = "empty"; click(R.id.track_refresh)
            await("empty success rendered without stale error/list") { !model.state.value.loading &&
                model.state.value.emptyVisible && view(R.id.emptyLayout).isShown && !list().isShown &&
                !view(R.id.track_message).isShown && model.state.value.rows.filterIsInstance<TrackRow.Point>().isEmpty() }
            pass("empty: empty overlay visible, stale list/error hidden"); screenshot("empty")
            finish(Activity.RESULT_OK, report())
        } catch (failure: Throwable) {
            val stack = Log.getStackTraceString(failure)
            finish(Activity.RESULT_CANCELED, report().apply { putString("error", stack); putString("stream", "FAILED\n$stack") })
        }
    }
}
