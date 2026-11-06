package com.youloft.linkup.location

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The reporting rule the recovered app applied before it looked at stay state at all, plus the loop that
 * files fixes with a backend.
 *
 * Deliberately pure JVM: no Android, no coroutine test library (`kotlinx-coroutines-test` is not in this
 * build's dependency cache and cannot be fetched), no fake clock. [ReportGate] is state-only and
 * synchronous by design, and the collector runs against a [Dispatchers.Unconfined] scope so a launched
 * flush has finished by the time `accept` returns -- which is what makes the ordering assertions below
 * mean something instead of racing.
 */
class ReportGateAndCollectorTest {

    // ------------------------------------------------------------------ cadence table

    @Test
    fun `cadenceSeconds is the recovered speed table`() {
        // x0.java:156-158: speed <= 0 -> maxInterval, else clamp(minUploadDistance / speed, min, max).
        val tuning = LocationTuning()
        assertEquals(20.0, tuning.cadenceSeconds(0f), 1e-9, "a stopped device reports every 20 s")
        assertEquals(20.0, tuning.cadenceSeconds(-1f), 1e-9, "the floor branch covers negative speeds too")
        assertEquals(20.0, tuning.cadenceSeconds(1.0f), 1e-9, "40/1 = 40 s, clamped to the 20 s ceiling")
        assertEquals(20.0, tuning.cadenceSeconds(2.0f), 1e-9, "40/2 = 20 s, exactly the ceiling")
        assertEquals(10.0, tuning.cadenceSeconds(4.0f), 1e-9, "40/4 = 10 s")
        assertEquals(5.0, tuning.cadenceSeconds(8.0f), 1e-9, "40/8 = 5 s, exactly the floor")
        assertEquals(5.0, tuning.cadenceSeconds(20.0f), 1e-9, "2 s would be faster than the 5 s floor")

        // Units are the whole point: Baidu reports km/h and the app divided by 3.6 before storing the speed
        // (x0.java:288). A provider that handed 14.4 km/h over as if it were m/s collapses the window from
        // 10 s to the 5 s floor, i.e. the app reports roughly twice as often as the recovered one did.
        assertEquals(5.0, tuning.cadenceSeconds(8f), 1e-9, "8 m/s hits the 5 s floor exactly")
        assertEquals(10.0, tuning.cadenceSeconds(4f), 1e-9, "4 m/s = 14.4 km/h -> a 10 s window")
        assertEquals(5.0, tuning.cadenceSeconds(14.4f), 1e-9, "the same speed left in km/h -> the floor")
    }

    @Test
    fun `a tuning override moves the whole table`() {
        val tuning = LocationTuning(minIntervalSeconds = 2, maxIntervalSeconds = 30, minUploadDistanceMeters = 60.0)
        assertEquals(30.0, tuning.cadenceSeconds(0f), 1e-9)
        assertEquals(30.0, tuning.cadenceSeconds(1f), 1e-9, "60/1 = 60 s, clamped to the 30 s ceiling")
        assertEquals(15.0, tuning.cadenceSeconds(4f), 1e-9)
        assertEquals(2.0, tuning.cadenceSeconds(60f), 1e-9, "1 s would be under the 2 s floor")
        assertEquals(30_000L, tuning.dormantRequest().intervalMillis)
        assertEquals(LocationAccuracy.BATTERY_SAVING, tuning.dormantRequest().accuracy)
        assertEquals(LocationRequest.FOREGROUND, tuning.followingRequest())
    }

    @Test
    fun `fromConfig overrides the cadence keys one by one and keeps the recovered fallback`() {
        // The reader shape is `manager/l.java:64-80`, which read ConfigUtils.d(key, default) per field.
        val defaults = LocationTuning()
        val readAll: (String) -> Double? = { key ->
            when (key) {
                LocationTuning.KEY_DORMANCY_INTERVAL_MILLIS -> 45_000.0
                LocationTuning.KEY_MIN_INTERVAL_SECONDS -> 3.0
                LocationTuning.KEY_MAX_INTERVAL_SECONDS -> 60.0
                LocationTuning.KEY_MIN_UPLOAD_DISTANCE_METERS -> 100.0
                else -> null
            }
        }

        val configured = LocationTuning.fromConfig(readAll)
        assertEquals(45_000L, configured.dormancyIntervalMillis)
        assertEquals(3L, configured.minIntervalSeconds)
        assertEquals(60L, configured.maxIntervalSeconds)
        assertEquals(100.0, configured.minUploadDistanceMeters, 1e-9)
        assertEquals(60.0, configured.cadenceSeconds(0f), 1e-9, "a stopped device now reports every minute")
        assertEquals(12.5, configured.cadenceSeconds(8f), 1e-9, "100 m / 8 m/s, above the new 3 s floor")
        assertEquals(45_000L, configured.dormantRequest().intervalMillis)
        assertEquals(defaults.pendingLimit, configured.pendingLimit, "pendingLimit is not a config key")

        // A config that carries nothing at all must land on the shipped defaults, not on zeros.
        assertEquals(defaults, LocationTuning.fromConfig { null })
        val partial = LocationTuning.fromConfig { if (it == LocationTuning.KEY_MAX_INTERVAL_SECONDS) 12.0 else null }
        assertEquals(12L, partial.maxIntervalSeconds)
        assertEquals(defaults.minIntervalSeconds, partial.minIntervalSeconds)
        assertEquals(defaults.minUploadDistanceMeters, partial.minUploadDistanceMeters)
    }

    // ------------------------------------------------------------------ ReportGate

    @Test
    fun `the first fix is always accepted and the next one inside the cadence is not`() {
        val gate = ReportGate()
        val first = fix(39.90469, 116.40717, epoch = 1_000_000, speed = 0f)

        assertNull(gate.lastReported)
        assertTrue(gate.shouldReport(first), "nothing has been reported yet")

        gate.reported(first)
        assertEquals(first, gate.lastReported, "the gate compares against what reached the server (x0.java:914)")

        assertFalse(gate.shouldReport(first.copy(epochSeconds = 1_000_019)), "19 s is inside the 20 s cadence")
        assertTrue(gate.shouldReport(first.copy(epochSeconds = 1_000_020)), "20 s meets it; the rule is >=, not >")
    }

    @Test
    fun `an invalid fix is never accepted`() {
        // x0.java:284-287 dropped the placeholder sample before the reporting decision was even reached.
        val gate = ReportGate()
        val placeholder = fix(0.0, 116.40717, epoch = 2_000_000, speed = 0f)

        assertFalse(gate.shouldReport(placeholder), "a (0,0) fix must not reach the server")
        gate.reported(fix(39.9, 116.4, epoch = 2_000_000, speed = 0f))
        assertFalse(gate.shouldReport(placeholder.copy(epochSeconds = 2_000_600)), "not even after the cadence")
        assertFalse(gate.shouldReport(fix(39.9, Double.MIN_VALUE, epoch = 2_000_600, speed = 0f)))
    }

    @Test
    fun `the cadence is the max of the two speeds so either fast fix shortens the window`() {
        // x0.java:218-228 compares elapsed time against cadence(max(previous.speed, current.speed)).
        val gate = ReportGate()
        gate.reported(fix(39.9, 116.4, epoch = 3_000_000, speed = 8f)) // fast PREVIOUS fix -> 5 s window

        assertFalse(gate.shouldReport(fix(39.9, 116.4, epoch = 3_000_004, speed = 0f)), "4 s is under 5 s")
        assertTrue(
            gate.shouldReport(fix(39.9, 116.4, epoch = 3_000_010, speed = 0f)),
            "a stopped fix 10 s later is accepted BECAUSE the previous one moved at 8 m/s; with only the " +
                "current speed the window would still have been 20 s and this sample would be filtered",
        )

        val idle = ReportGate()
        idle.reported(fix(39.9, 116.4, epoch = 3_000_000, speed = 0f))

        assertFalse(idle.shouldReport(fix(39.9, 116.4, epoch = 3_000_004, speed = 8f)), "4 s is under the 5 s " +
            "the max rule gives even when only the CURRENT fix moved")
        assertTrue(idle.shouldReport(fix(39.9, 116.4, epoch = 3_000_010, speed = 8f)), "the rule is symmetric")
        assertFalse(idle.shouldReport(fix(39.9, 116.4, epoch = 3_000_004, speed = 0f)), "0/0 -> the 20 s ceiling")
    }

    @Test
    fun `reset forgets what was reported`() {
        val gate = ReportGate()
        val first = fix(39.9, 116.4, epoch = 4_000_000, speed = 0f)
        gate.reported(first)
        assertFalse(gate.shouldReport(first.copy(epochSeconds = 4_000_001)))

        gate.reset()
        assertNull(gate.lastReported)
        assertTrue(gate.shouldReport(first.copy(epochSeconds = 4_000_001)))
    }

    // ------------------------------------------------------------------ collector

    @Test
    fun `an accepted fix is reported once as unclassified`() = runBlocking {
        val provider = FakeProvider()
        val gateway = RecordingGateway()
        val events = mutableListOf<String>()
        val collector = collector(provider, gateway, events = events)

        assertTrue(collector.start())
        val accepted = fix(39.90469, 116.40717, epoch = 5_000_000, speed = 0f)
        collector.accept(accepted)

        assertEquals(
            listOf(accepted to ReportKind.UNCLASSIFIED),
            gateway.reported,
            "the stay state machine does not exist yet, so everything goes out as type 0 (x0.java:304)",
        )
        assertEquals(0, collector.pendingCount(), "an accepted fix on a working gateway is flushed inline")
        assertTrue(events.any { it.contains("定位上报 ok") && it.contains("剩余=0") }, events.toString())
    }

    @Test
    fun `a fix the provider pushes is filtered by the gate before it is reported`() = runBlocking {
        val provider = FakeProvider()
        val gateway = RecordingGateway()
        val events = mutableListOf<String>()
        val collector = collector(provider, gateway, events = events)
        assertTrue(collector.start())

        val listener = assertNotNull(provider.listener, "start() handed the collector's listener over")
        listener.onFix(fix(39.9, 116.4, epoch = 5_100_000, speed = 0f))
        listener.onFix(fix(39.9, 116.4, epoch = 5_100_005, speed = 0f))
        listener.onFix(fix(39.9, 116.4, epoch = 5_100_020, speed = 0f))

        assertEquals(
            listOf(5_100_000L, 5_100_020L),
            gateway.reported.map { it.first.epochSeconds },
            "the 5 s follow-up is filtered by the 20 s cadence, exactly as x0.java:218-228 did",
        )
        assertTrue(events.any { it.contains("过滤") && it.contains("不足节律") }, events.toString())
    }

    @Test
    fun `a gateway that throws keeps the fix pending and a later flush delivers it in order`() = runBlocking {
        val provider = FakeProvider()
        val gateway = RecordingGateway()
        val events = mutableListOf<String>()
        val collector = collector(provider, gateway, events = events)
        collector.start()

        gateway.failing = true
        val first = fix(39.9, 116.4, epoch = 6_000_000, speed = 0f)
        val second = fix(39.91, 116.41, epoch = 6_000_030, speed = 0f)
        collector.accept(first)
        collector.accept(second)

        assertEquals(2, collector.pendingCount(), "an outage must not lose fixes")
        assertTrue(gateway.reported.isEmpty())
        assertTrue(events.any { it.contains("定位上报 失败") && it.contains("剩余=2") }, events.toString())

        gateway.failing = false
        collector.flush()

        assertEquals(0, collector.pendingCount())
        assertEquals(
            listOf(first, second),
            gateway.reported.map { it.first },
            "oldest first: order survives the outage, unlike a queue that retries per fix",
        )
        assertEquals(
            listOf(ReportKind.UNCLASSIFIED, ReportKind.UNCLASSIFIED),
            gateway.reported.map { it.second },
        )
    }

    @Test
    fun `the pending queue is bounded and drops the oldest fix`() = runBlocking {
        val provider = FakeProvider()
        val gateway = RecordingGateway()
        val collector = collector(provider, gateway, tuning = LocationTuning(pendingLimit = 2))
        collector.start()
        gateway.failing = true

        val fixes = (0 until 4).map { fix(39.9, 116.4, epoch = 7_000_000L + it * 60, speed = 0f) }
        fixes.forEach { collector.accept(it) }

        assertEquals(2, collector.pendingCount(), "LocationTuning.pendingLimit caps the queue on purpose; " +
            "the original's LinkedBlockingQueue (x0.java:63) was unbounded")
        gateway.failing = false
        collector.flush()

        assertEquals(
            listOf(fixes[2], fixes[3]),
            gateway.reported.map { it.first },
            "the OLDEST fixes are the ones dropped, because a stale position is not worth a track row",
        )
    }

    @Test
    fun `a flush stops at the first transport failure so later fixes keep their place`() = runBlocking {
        val provider = FakeProvider()
        val gateway = RecordingGateway()
        val collector = collector(provider, gateway)
        collector.start()
        gateway.failing = true

        val first = fix(39.9, 116.4, epoch = 8_000_000, speed = 0f)
        val second = fix(39.91, 116.4, epoch = 8_000_030, speed = 0f)
        collector.accept(first)
        collector.accept(second)
        assertEquals(2, collector.pendingCount())

        gateway.failing = false
        gateway.failingFix = second // only the second send breaks
        collector.flush()

        assertEquals(listOf(first), gateway.reported.map { it.first }, "the good prefix went out")
        assertEquals(1, collector.pendingCount(), "the failed one and everything behind it stay queued")

        gateway.failingFix = null
        collector.flush()
        assertEquals(0, collector.pendingCount())
        assertEquals(listOf(first, second), gateway.reported.map { it.first })
    }

    @Test
    fun `backgrounding swaps the live request to the dormant one and back`() = runBlocking {
        val provider = FakeProvider()
        val tuning = LocationTuning()
        val collector = collector(provider, RecordingGateway(), tuning = tuning)
        assertTrue(collector.start())

        assertEquals(
            listOf(tuning.followingRequest()),
            provider.started,
            "a fresh collector follows in the foreground, even if it later backgrounds",
        )

        assertTrue(collector.setBackgrounded(true), "the provider took the dormancy request")
        assertEquals(listOf(tuning.dormantRequest()), provider.updated, "s0.java:810 -> Battery_Saving + 30 s")
        assertEquals(30_000L, provider.updated.single().intervalMillis)
        assertEquals(LocationAccuracy.BATTERY_SAVING, provider.updated.single().accuracy)

        assertTrue(collector.setBackgrounded(false))
        assertEquals(
            listOf(tuning.dormantRequest(), tuning.followingRequest()),
            provider.updated,
            "coming back restores the foreground request",
        )
        assertEquals(2_000L, provider.updated.last().intervalMillis)
        assertEquals(LocationAccuracy.HIGH, provider.updated.last().accuracy)

        assertFalse(collector.setBackgrounded(false), "already in that state: nothing to reconfigure")
        assertEquals(2, provider.updated.size)
    }

    @Test
    fun `a collector that starts backgrounded follows the dormant request`() = runBlocking {
        val provider = FakeProvider()
        val collector = collector(provider, RecordingGateway())

        assertTrue(collector.setBackgrounded(true), "the state flips before the provider is even started")
        assertTrue(collector.start())
        assertEquals(listOf(LocationTuning().dormantRequest()), provider.started)
    }

    @Test
    fun `start answers with whatever the provider says`() = runBlocking {
        val provider = FakeProvider(startAnswer = false)
        val events = mutableListOf<String>()
        val collector = collector(provider, RecordingGateway(), events = events)

        assertFalse(collector.start(), "the caller has to fall through to the next LocationProviders candidate")
        assertTrue(provider.started.isNotEmpty(), "it was still asked, so the refusal is auditable")
        assertTrue(events.any { it.contains("start provider=fake ok=false") }, events.toString())
    }

    @Test
    fun `stop hands the provider back`() = runBlocking {
        val provider = FakeProvider()
        val collector = collector(provider, RecordingGateway())
        collector.start()

        collector.stop()
        assertEquals(1, provider.stopCount)
    }

    @Test
    fun `a provider failure is reported as an event and not thrown`() = runBlocking {
        val provider = FakeProvider()
        val events = mutableListOf<String>()
        val collector = collector(provider, RecordingGateway(), events = events)
        collector.start()

        assertNotNull(provider.listener).onFailure(LocationFault(LocationFailure.TIMEOUT, vendorCode = 61))

        val event = events.single { it.contains("定位失败") }
        assertTrue(event.contains("TIMEOUT"), event)
        assertTrue(event.contains("vendor=61"), event)
        assertTrue(event.contains("provider=fake"), event)
    }

    // ------------------------------------------------------------------ helpers

    private fun collector(
        provider: FakeProvider,
        gateway: RecordingGateway,
        tuning: LocationTuning = LocationTuning(),
        events: MutableList<String> = mutableListOf(),
    ): LocationCollector = LocationCollector(
        provider = provider,
        gateway = gateway,
        tuning = tuning,
        // Unconfined: `scope.launch { flush() }` inside accept() runs to completion before accept returns,
        // so these tests assert on outcomes rather than on scheduling.
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        onEvent = { events += it },
    )

    /** A valid WGS84 fix; only the fields the gate reads vary between tests. */
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
        var failingFix: LocationFix? = null
        var attempts = 0

        override suspend fun report(fix: LocationFix, kind: ReportKind) {
            attempts++
            if (failing || (failingFix != null && failingFix == fix)) throw IOException("upstream down")
            reported += fix to kind
        }
    }

    /** Records every [LocationRequest] it was handed, which is what the dormancy tests read. */
    private class FakeProvider(private val startAnswer: Boolean = true) : LocationProvider {
        val started = mutableListOf<LocationRequest>()
        val updated = mutableListOf<LocationRequest>()
        var listener: LocationListener? = null
        var stopCount = 0

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
            return startAnswer
        }

        override fun update(request: LocationRequest): Boolean {
            updated += request
            return true
        }

        override fun stop() {
            stopCount++
        }

        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.TIMEOUT))

        override fun lastFix(): LocationFix? = null
        override fun close() = Unit
    }
}
