package com.youloft.linkup.location

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The collector with the stay/leave classifier configured — the shape the service card runs in. The
 * cadence-only shape keeps its own suite in [ReportGateAndCollectorTest]; what is new here is that kinds
 * reach the gateway, the widget copy fires with the original's `R()` rule, and the intake pacer re-points
 * the provider in the foreground only.
 */
class CollectorWithClassifierTest {

    @Test
    fun `a maturing stay goes out with its STAY kind, and the widget copy follows`() = runBlocking {
        val gateway = RecordingGateway()
        val widgets = mutableListOf<Pair<LocationFix, ReportKind>>()
        val classifier = StayLeaveClassifier()
        val collector = collector(gateway, widgets, classifier)

        val potential = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 1f)
        collector.accept(potential)
        collector.accept(potential.copy(epochSeconds = 1_000_301, coord = Coord(39.90479, 116.40717)))

        assertEquals(
            listOf(ReportKind.UNCLASSIFIED, ReportKind.STAY),
            gateway.reported.map { it.second },
            "the maturing fix carries the arrival marker the server reads as an arrival row",
        )
        assertEquals(
            listOf(ReportKind.UNCLASSIFIED, ReportKind.STAY),
            widgets.map { it.second },
            "the classified fix passes through R() unchanged; type 4 is for unclassified copies only — " +
                "see the parked-spacing test in StayLeaveClassifierTest for that branch",
        )
    }

    @Test
    fun `an unclassified fix inside a stay reaches the widget as type 4`() = runBlocking {
        val gateway = RecordingGateway()
        val widgets = mutableListOf<Pair<LocationFix, ReportKind>>()
        val classifier = StayLeaveClassifier()
        val collector = collector(gateway, widgets, classifier)

        val potential = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 1f)
        collector.accept(potential)
        val maturing = potential.copy(epochSeconds = 1_000_301, coord = Coord(39.90479, 116.40717))
        collector.accept(maturing)

        // Parked, low-speed, past the 900 s spacing: reported unclassified, widget copy stamped 4 (R()).
        collector.accept(maturing.copy(epochSeconds = 1_000_301 + 901))

        assertEquals(ReportKind.UNCLASSIFIED, gateway.reported.last().second)
        assertEquals(ReportKind.UNCHANGED, widgets.last().second)
    }

    @Test
    fun `a judged departure goes out as LEAVE and clears the widget marker`() = runBlocking {
        val gateway = RecordingGateway()
        val widgets = mutableListOf<Pair<LocationFix, ReportKind>>()
        val classifier = StayLeaveClassifier()
        val collector = collector(gateway, widgets, classifier)
        val potential = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 1f)
        collector.accept(potential)
        collector.accept(potential.copy(epochSeconds = 1_000_301, coord = Coord(39.90479, 116.40717)))
        assertTrue(gateway.reported.map { it.second }.contains(ReportKind.STAY))

        collector.accept(fix(39.90969, 116.40717, epoch = 1_000_700, speed = 6f)) // ~556 m away

        assertEquals(ReportKind.LEAVE, gateway.reported.last().second)
        assertEquals(ReportKind.LEAVE, widgets.last().second, "a classified fix passes through R() unchanged")
        assertNull(classifier.stayPoint)
    }

    @Test
    fun `a fix the classifier filters never reaches the queue`() = runBlocking {
        val gateway = RecordingGateway()
        val classifier = StayLeaveClassifier()
        val collector = collector(gateway, mutableListOf(), classifier)

        val reported = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 2f)
        collector.accept(reported)
        collector.accept(reported.copy(epochSeconds = 1_000_010, coord = Coord(39.90569, 116.40717)))

        assertEquals(
            listOf(1_000_000L),
            gateway.reported.map { it.first.epochSeconds },
            "cadence(2 m/s) = 20 s: the classifier gates before the queue, exactly as c() did in the worker",
        )
        assertEquals(0, collector.pendingCount())
    }

    @Test
    fun `queued kinds survive a network outage in order`() = runBlocking {
        val gateway = RecordingGateway()
        val classifier = StayLeaveClassifier()
        val collector = collector(gateway, mutableListOf(), classifier)
        gateway.failing = true

        val potential = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 1f)
        val maturing = potential.copy(epochSeconds = 1_000_301, coord = Coord(39.90479, 116.40717))
        collector.accept(potential)
        collector.accept(maturing)
        assertEquals(2, collector.pendingCount())

        gateway.failing = false
        collector.flush()

        assertEquals(
            listOf(ReportKind.UNCLASSIFIED, ReportKind.STAY),
            gateway.reported.map { it.second },
            "a fix keeps the kind it was judged with, however long the outage lasted",
        )
    }

    @Test
    fun `an invalid fix is dropped before the classifier sees it`() = runBlocking {
        val gateway = RecordingGateway()
        val events = mutableListOf<String>()
        val collector = LocationCollector(
            provider = FakeProvider(),
            gateway = gateway,
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            onEvent = { events += it },
            classifier = StayLeaveClassifier(),
        )

        collector.accept(fix(0.0, 116.40717, epoch = 1_000_000, speed = 0f))

        assertTrue(gateway.reported.isEmpty())
        assertTrue(events.any { it.contains("无效点") }, events.toString())
    }

    @Test
    fun `the intake pacer re-points the provider in the foreground only`() = runBlocking {
        val provider = FakeProvider()
        val collector = LocationCollector(
            provider = provider,
            gateway = RecordingGateway(),
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            classifier = StayLeaveClassifier(),
        )
        collector.start()

        collector.accept(fix(39.9, 116.4, epoch = 1_000_000, speed = 6f))
        assertEquals(
            listOf(2_000L),
            provider.updated.map { it.intervalMillis },
            "a fast fix asks for the 2 s loop (x0.java:322-326)",
        )

        provider.updated.clear()
        collector.setBackgrounded(true)
        provider.updated.clear()
        collector.accept(fix(39.9, 116.4, epoch = 1_000_100, speed = 6f))
        assertTrue(
            provider.updated.none { it.intervalMillis == 2_000L },
            "backgrounded, the dormancy request owns the interval; the pacer stays out of it",
        )
    }

    @Test
    fun `the widget callback fires only after the gateway took the fix`() = runBlocking {
        val gateway = RecordingGateway()
        val widgets = mutableListOf<Pair<LocationFix, ReportKind>>()
        val collector = collector(gateway, widgets, StayLeaveClassifier())
        gateway.failing = true

        collector.accept(fix(39.90469, 116.40717, epoch = 1_000_000, speed = 1f))
        assertTrue(widgets.isEmpty(), "a failed report pushes no widget copy")

        gateway.failing = false
        collector.flush()
        assertEquals(1, widgets.size, "R() ran in the success continuation, and so does this")
    }

    // ------------------------------------------------------------------ helpers

    private fun collector(
        gateway: RecordingGateway,
        widgets: MutableList<Pair<LocationFix, ReportKind>>,
        classifier: StayLeaveClassifier,
        provider: FakeProvider = FakeProvider(),
    ): LocationCollector = LocationCollector(
        provider = provider,
        gateway = gateway,
        // Unconfined, as in [ReportGateAndCollectorTest]: a launched flush finishes before accept returns.
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        classifier = classifier,
        onWidget = { fix, kind -> widgets += fix to kind },
    )

    private fun fix(lat: Double, lng: Double, epoch: Long, speed: Float) = LocationFix(
        coord = Coord(lat, lng, CoordSystem.WGS84),
        epochSeconds = epoch,
        accuracyMeters = 25f,
        speedMetersPerSecond = speed,
        providerId = "fake",
    )

    private class RecordingGateway : LocationGateway {
        val reported = mutableListOf<Pair<LocationFix, ReportKind>>()
        var failing = false

        override suspend fun report(fix: LocationFix, kind: ReportKind) {
            if (failing) throw IOException("upstream down")
            reported += fix to kind
        }
    }

    private class FakeProvider : LocationProvider {
        val started = mutableListOf<LocationRequest>()
        val updated = mutableListOf<LocationRequest>()
        var listener: LocationListener? = null

        override val id = "fake"
        override val capabilities = LocationCapabilities(
            nativeCoordSystem = CoordSystem.WGS84,
            inlinePlace = false,
            poiList = false,
            needsVendorKey = false,
            supportsRuntimeInterval = true,
        )
        override val isInstalled: Boolean get() = true

        override fun start(request: LocationRequest, listener: LocationListener): Boolean {
            started += request
            this.listener = listener
            return true
        }

        override fun update(request: LocationRequest): Boolean {
            updated += request
            return true
        }

        override fun stop() = Unit

        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.TIMEOUT))

        override fun lastFix(): LocationFix? = null
        override fun close() = Unit
    }
}
