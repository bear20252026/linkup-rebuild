package com.youloft.watcher.pages.track

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.watcher.R
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-mdpi")
class TrackFormattingTest {
    private lateinit var context: Context
    private lateinit var formatter: TrackFormatter
    private lateinit var builder: TrackRowBuilder
    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.CHINA)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        formatter = TrackFormatter(context)
        builder = TrackRowBuilder(formatter)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun `summary distance includes 200m and keeps one decimal and km`() {
        val cases = listOf(
            -1.0 to "0",
            0.0 to "0",
            0.1 to "小于200m",
            199.9 to "小于200m",
            200.0 to "小于200m",
            200.1 to "0.2km",
            249.0 to "0.2km",
            250.0 to "0.3km",
            3000.0 to "3.0km",
            3050.0 to "3.1km",
        )
        cases.forEach { (meters, expected) -> assertEquals(expected, formatter.distance(meters), "$meters m") }
    }

    @Test
    fun `move distance excludes 200m and removes trailing zeros`() {
        val cases = listOf(
            0.0 to "小于200m",
            199.9 to "小于200m",
            200.0 to "0.2km",
            200.1 to "0.2km",
            250.0 to "0.3km",
            3000.0 to "3km",
            3050.0 to "3.1km",
            10_000.0 to "10km",
        )
        cases.forEach { (meters, expected) -> assertEquals(expected, formatter.bubbleDistance(meters), "$meters m") }
        assertEquals("0.2km • 0分钟", formatter.moveBubble(200.0, 30_000))
        assertEquals("3km • 1小时1分", formatter.moveBubble(3000.0, 3_719_999))
    }

    @Test
    fun `summary and move durations truncate at minute and hour boundaries`() {
        val cases = listOf(
            0L to "0分钟",
            29_999L to "0分钟",
            30_000L to "0分钟",
            59_999L to "0分钟",
            60_000L to "1分钟",
            3_599_999L to "59分钟",
            3_600_000L to "1小时",
            3_660_000L to "1小时1分",
            86_400_000L to "24小时",
        )
        cases.forEach { (millis, expected) -> assertEquals(expected, formatter.stayDuration(millis), "$millis ms") }
    }

    @Test
    fun `stay and unknown bubbles round at thirty seconds with empty fallback`() {
        val cases = listOf(
            0L to "小于1分钟",
            29L to "小于1分钟",
            30L to "1分钟",
            59L to "1分钟",
            89L to "1分钟",
            90L to "2分钟",
            3569L to "59分钟",
            3570L to "1小时",
            3630L to "1小时1分",
        )
        for (type in listOf(1, 5)) {
            cases.forEach { (seconds, expected) ->
                val row = builder.row(track(lineType = type, seconds = seconds))
                assertEquals(expected, assertNotNull(row.line).text, "type=$type seconds=$seconds")
            }
        }
        assertEquals("", formatter.duration(29_999))
        assertEquals("1分钟", formatter.duration(30_000))
    }

    @Test
    fun `composed duration carries hours and days without showing minutes after days`() {
        val cases = listOf(
            5_400_000L to "1小时30分",
            86_369_999L to "23小时59分",
            86_370_000L to "1天",
            88_169_999L to "1天",
            88_170_000L to "1天1小时",
            171_000_000L to "2天",
        )
        cases.forEach { (millis, expected) -> assertEquals(expected, formatter.duration(millis), "$millis ms") }
        assertEquals("29秒", formatter.duration(29_000, round = false, showSeconds = true))
        assertEquals("1小时1分30秒", formatter.duration(3_690_000, round = false, showSeconds = true))
        assertEquals("1天", formatter.duration(86_430_000, round = false, showSeconds = true))
    }

    @Test
    fun `summary always leads filtered newest-first tracks and seconds become long milliseconds`() {
        val oldest = track(time = 10)
        val newest = track(time = 30)
        val middle = track(time = 20)
        val input = listOf(oldest, null, newest, middle, null)
        val rows = builder.build(
            TrackRecordBean(
                arrivalPointCount = 7,
                totalStayTime = Int.MAX_VALUE,
                totalDistance = 3000.0,
                tracks = input,
            ),
        )
        assertEquals(4, rows.size)
        assertEquals(TrackRow.Summary(7, 2_147_483_647_000L, 3000.0, false), rows.first())
        assertEquals(listOf(30L, 20L, 10L), rows.drop(1).map { assertIs<TrackRow.Point>(it).view.track.timePeriod })
        assertSame(newest, assertIs<TrackRow.Point>(rows[1]).view.track)
        assertEquals(listOf(10L, null, 30L, 20L, null), input.map { it?.timePeriod })
        for (tracks in listOf(null, emptyList(), listOf(null))) {
            assertEquals(listOf(TrackRow.Summary(0, 0, 0.0, false)), builder.build(TrackRecordBean(tracks = tracks)))
        }
    }

    @Test
    fun `normal points distinguish arrive leave recording and visible blank labels`() {
        assertEquals(PointVisual(PointIcon.STAY, R.string.arrive, "公园"), builder.row(track(type = 2)).point)
        assertEquals(PointVisual(PointIcon.LEAVE, R.string.leave, null), builder.row(track(type = 3)).point)
        assertEquals(PointVisual(PointIcon.RECORDING, R.string.recording, null), builder.row(track(type = 4)).point)
        for (type in listOf(-1, 0, 1, 5, 99)) {
            assertEquals(PointVisual(PointIcon.PLAIN, PointVisual.BLANK_LABEL, null), builder.row(track(type = type)).point)
        }
    }

    @Test
    fun `lost types override all normal point labels and addresses`() {
        for (type in listOf(2, 3, 4, 99)) {
            for (lost in listOf(1, 3)) {
                assertEquals(
                    PointVisual(PointIcon.LOST_RED, null, null),
                    builder.row(track(type = type, lost = lost)).point,
                )
            }
            assertEquals(PointVisual(PointIcon.PLAIN, null, "公园"), builder.row(track(type = type, lost = 2)).point)
        }
    }

    @Test
    fun `unknown lost type resets to a neutral point independently of previous rows`() {
        val neutral = PointVisual(PointIcon.PLAIN, null, null)
        for (previousLost in listOf(0, 1, 2, 3)) {
            builder.row(track(type = 2, lost = previousLost))
            for (lost in listOf(-1, 4, 99)) {
                for (type in listOf(2, 3, 4, 99)) {
                    assertEquals(neutral, builder.row(track(type = type, lost = lost)).point)
                }
            }
            assertEquals(PointVisual(PointIcon.STAY, R.string.arrive, "公园"), builder.row(track(type = 2)).point)
        }
    }

    @Test
    fun `line five suppresses both ordinary arrival and lost two addresses`() {
        assertEquals(
            PointVisual(PointIcon.STAY, R.string.arrive, null),
            builder.row(track(type = 2, lineType = 5)).point,
        )
        assertEquals(
            PointVisual(PointIcon.PLAIN, null, null),
            builder.row(track(type = 2, lost = 2, lineType = 5)).point,
        )
        assertEquals("公园", builder.row(track(type = 2, lost = 2, lineType = 1)).point.address)
    }

    @Test
    fun `five line kinds have exact copy speed and click behavior`() {
        val expected = listOf(
            LineVisual(LineKind.STAY, "1分钟"),
            LineVisual(LineKind.MOVE, "1km • 1分钟", speed = SpeedIcon.CAR),
            LineVisual(LineKind.NO_LOCATION, "Ta关闭了始终定位权限，无法获取位置信息"),
            LineVisual(LineKind.NO_NEW_LOCATION, "没有新的位置信息", opensReasonDialog = true),
            LineVisual(LineKind.UNKNOWN, "1分钟"),
        )
        expected.forEachIndexed { index, line ->
            val row = builder.row(track(lineType = index + 1, seconds = 60, meters = 1000.0))
            assertEquals(line, row.line)
            assertEquals(index < 2, row.selectable)
        }
        for (type in listOf(null, -1, 0, 6, 99)) {
            val row = builder.row(track(lineType = type))
            assertNull(row.line)
            assertFalse(row.selectable)
        }
    }

    @Test
    fun `speed icons switch exactly at ten twenty-five one-twenty and three-fifty`() {
        val cases = listOf(
            -1.0 to SpeedIcon.WALK,
            0.0 to SpeedIcon.WALK,
            9.999 to SpeedIcon.WALK,
            10.0 to SpeedIcon.BICYCLE,
            24.999 to SpeedIcon.BICYCLE,
            25.0 to SpeedIcon.CAR,
            119.999 to SpeedIcon.CAR,
            120.0 to SpeedIcon.TRAIN,
            349.999 to SpeedIcon.TRAIN,
            350.0 to SpeedIcon.PLANE,
            Double.POSITIVE_INFINITY to SpeedIcon.PLANE,
            Double.NaN to SpeedIcon.CAR,
        )
        cases.forEach { (speed, expected) -> assertEquals(expected, TrackRowBuilder.speedIconOf(speed), "$speed km/h") }
        assertEquals(59.99999841054281, TrackRowBuilder.speedOf(1000.0, 60), 1e-12)
        assertEquals(SpeedIcon.CAR, TrackRowBuilder.speedIcon(1000.0, 60))
        assertEquals(SpeedIcon.WALK, TrackRowBuilder.speedIcon(1000.0, 360))
        assertEquals(SpeedIcon.PLANE, TrackRowBuilder.speedIcon(1.0, 0))
        assertTrue(TrackRowBuilder.speedOf(0.0, 0).isNaN())
        assertEquals(SpeedIcon.CAR, TrackRowBuilder.speedIcon(0.0, 0))
    }

    @Test
    fun `clock interprets epoch seconds using the device time zone`() {
        assertEquals("00:00", formatter.clock(0))
        assertEquals("22:13", formatter.clock(1_700_000_000))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals("08:00", formatter.clock(0))
        assertEquals("06:13", formatter.clock(1_700_000_000))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `digits use twenty sp in pixels and heavy typeface with exclusive boundaries`() {
        val metrics = context.resources.displayMetrics
        val oldDensity = metrics.density
        val oldScaledDensity = metrics.scaledDensity
        try {
            metrics.density = 1f
            metrics.scaledDensity = 1.5f
            val text = assertIs<Spanned>(formatter.emphasiseDigits("12.5km 2小时 .5m"))
            val ranges = listOf(0 to 4, 7 to 8, 11 to 13)
            val sizes = text.getSpans(0, text.length, AbsoluteSizeSpan::class.java)
            assertEquals(3, sizes.size)
            assertEquals(ranges, sizes.map { text.getSpanStart(it) to text.getSpanEnd(it) })
            sizes.forEach {
                assertEquals(30, it.size)
                assertFalse(it.dip)
                assertEquals(33, text.getSpanFlags(it))
                val paint = TextPaint().apply { density = 2f }
                it.updateMeasureState(paint)
                assertEquals(30f, paint.textSize)
            }
            val fonts = text.getSpans(0, text.length, MetricAffectingSpan::class.java)
                .filterNot { it is AbsoluteSizeSpan }
            assertEquals(3, fonts.size)
            assertEquals(ranges, fonts.map { text.getSpanStart(it) to text.getSpanEnd(it) })
            val expectedFont = assertNotNull(ResourcesCompat.getFont(context, R.font.sf_pro_rounded_heavy_subset))
            fonts.forEach {
                assertEquals(33, text.getSpanFlags(it))
                val draw = TextPaint().apply { typeface = Typeface.DEFAULT }
                val measure = TextPaint().apply { typeface = Typeface.DEFAULT }
                it.updateDrawState(draw)
                it.updateMeasureState(measure)
                assertEquals(expectedFont, draw.typeface)
                assertEquals(expectedFont, measure.typeface)
            }
        } finally {
            metrics.density = oldDensity
            metrics.scaledDensity = oldScaledDensity
        }
    }

    @Test
    fun `inserting adjacent unit text does not extend digit spans`() {
        val text = assertIs<SpannableStringBuilder>(formatter.emphasiseDigits("12"))
        text.insert(0, "约")
        text.append("km")
        val spans = text.getSpans(0, text.length, MetricAffectingSpan::class.java)
        assertEquals(2, spans.size)
        spans.forEach {
            assertEquals(1, text.getSpanStart(it))
            assertEquals(3, text.getSpanEnd(it))
            assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(it))
        }
        assertEquals("", formatter.emphasiseDigits(""))
        val noDigits = assertIs<Spanned>(formatter.emphasiseDigits("公里"))
        assertEquals(0, noDigits.getSpans(0, noDigits.length, MetricAffectingSpan::class.java).size)
    }

    private fun track(
        type: Int = 2,
        lost: Int = 0,
        lineType: Int? = null,
        seconds: Long = 0,
        meters: Double = 0.0,
        time: Long = 0,
    ) = TrackRecordBean.Track(
        type = type,
        lostType = lost,
        address = "公园",
        timePeriod = time,
        line = lineType?.let { TrackRecordBean.Line(type = it, duration = seconds, distance = meters) },
    )
}
