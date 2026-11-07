package com.youloft.linkup.location

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The truth table the design doc once demanded before porting `x0.java:164-265` — one test per branch of
 * the decompiled gate, named after the original's own log line so a failure names the rule it broke.
 *
 * Geometry: at 39.9°N one degree of latitude is ~111.2 km (`0.001` ≈ 111 m, `0.0001` ≈ 11 m), and every
 * fixture lands far enough from the threshold it tests that float noise cannot flip a branch. Accuracy is
 * 25 m on offset fixes and 0 on the origin, which matters only in the stay-state branches — that is where
 * the original subtracts both accuracies, and the tests keep that asymmetry visible.
 */
class StayLeaveClassifierTest {

    private val tuning = LocationTuning()
    private val classifier = StayLeaveClassifier(tuning)

    // ------------------------------------------------------------------ non-stay state (potential accumulation)

    @Test
    fun `non-stay low-speed first fix marks the potential stay and reports`() {
        val decision = classifier.classify(fix(0.0, speed = 1.0))

        assertTrue(decision.report)
        assertEquals(ReportKind.UNCLASSIFIED, decision.kind)
        assertTrue(decision.reason.contains("标记为潜在停留点"))
        assertEquals(
            fix(0.0, speed = 1.0).coord.lat,
            assertNotNull(classifier.potentialStayPoint).coord.lat,
            1e-9,
        )
    }

    @Test
    fun `non-stay high-speed fix reports without touching the potential stay`() {
        classifier.classify(fix(0.0, speed = 1.0))
        val potentialBefore = classifier.potentialStayPoint

        val decision = classifier.classify(fix(0.001, speed = 6.0))

        assertTrue(decision.report)
        assertEquals(ReportKind.UNCLASSIFIED, decision.kind)
        assertTrue(decision.reason.contains("高速点"))
        assertEquals(potentialBefore, classifier.potentialStayPoint, "speed, not distance, drives stay state")
    }

    @Test
    fun `non-stay fix inside the moving cadence is filtered`() {
        val reported = fix(0.0, epoch = T0, speed = 2.0)
        classifier.onReported(reported)

        val decision = classifier.classify(fix(0.0005, epoch = T0 + 10, speed = 2.0))

        assertFalse(decision.report, "cadence(2 m/s) = 20 s; 10 s in is filtered")
        assertTrue(decision.reason.contains("过滤-非停留状态-上报间隔未超过最小时间间隔"))
    }

    @Test
    fun `non-stay low-speed beyond the potential range marks a new potential stay`() {
        // 待验 62, shipping reading: the exit radius is stayDistanceThreshold (200 m).
        classifier.classify(fix(0.0, speed = 1.0))

        val decision = classifier.classify(fix(0.003, epoch = T0 + 100, speed = 1.0)) // ~333 m away

        assertTrue(decision.report)
        assertTrue(decision.reason.contains("标记为新的潜在停留点"))
        assertEquals(39.90469 + 0.003, assertNotNull(classifier.potentialStayPoint).coord.lat, 1e-9)
    }

    @Test
    fun `non-stay low-speed within 20 m of a young potential stay is filtered`() {
        classifier.classify(fix(0.0, epoch = T0, speed = 1.0))

        val decision = classifier.classify(fix(0.0001, epoch = T0 + 100, speed = 1.0)) // ~11 m, 100 s later

        assertFalse(decision.report)
        assertTrue(decision.reason.contains("距离潜在停留点未超过"))
    }

    @Test
    fun `non-stay low-speed 20-200 m from a young potential stay reports`() {
        classifier.classify(fix(0.0, epoch = T0, speed = 1.0))

        val decision = classifier.classify(fix(0.0005, epoch = T0 + 100, speed = 1.0)) // ~56 m, inside 200 m

        assertTrue(decision.report)
        assertTrue(decision.reason.contains("距离潜在停留点超过"))
    }

    @Test
    fun `potential stay older than the threshold matures into a stay`() {
        val potential = fix(0.0, epoch = T0, speed = 1.0)
        classifier.classify(potential)

        val decision = classifier.classify(fix(0.0001, epoch = T0 + tuning.stayThresholdSeconds + 1, speed = 1.0))

        assertTrue(decision.report)
        assertEquals(ReportKind.STAY, decision.kind, "the maturing fix carries the arrival marker on the wire")
        assertTrue(decision.reason.contains("潜在停留超过"))
        assertEquals(potential.coord.lat, assertNotNull(classifier.stayPoint).coord.lat, 1e-9)
        assertNull(classifier.potentialStayPoint, "the accumulator is spent")
    }

    // ------------------------------------------------------------------ stay state (parked)

    /** Walks the machine into the stay state and returns the potential fix that became the stay. */
    private fun enterStay(): LocationFix {
        val potential = fix(0.0, epoch = T0, speed = 1.0)
        classifier.classify(potential)
        val maturing = classifier.classify(fix(0.0001, epoch = T0 + tuning.stayThresholdSeconds + 1, speed = 1.0))
        assertTrue(maturing.report && maturing.kind == ReportKind.STAY)
        return potential
    }

    @Test
    fun `parked low-speed inside the stay radius waits for the ten-minute spacing`() {
        enterStay()
        val t0 = T0 + tuning.stayThresholdSeconds + 1
        classifier.onReported(fix(0.0001, epoch = t0, speed = 1.0))

        val tooSoon = classifier.classify(fix(0.0002, epoch = t0 + tuning.stayMinTimeIntervalSeconds - 1, speed = 1.0))
        assertFalse(tooSoon.report, "899 s in: filtered")
        assertTrue(tooSoon.reason.contains("过滤-停留状态-停留范围内-低速点"))

        val spaced = classifier.classify(fix(0.0003, epoch = t0 + tuning.stayMinTimeIntervalSeconds + 1, speed = 1.0))
        assertTrue(spaced.report, "901 s in: reported")
        assertEquals(ReportKind.UNCLASSIFIED, spaced.kind)
    }

    @Test
    fun `parked high-speed inside the stay radius follows the moving cadence`() {
        enterStay()
        val t0 = T0 + tuning.stayThresholdSeconds + 1
        classifier.onReported(fix(0.0001, epoch = t0, speed = 0.5))

        val decision = classifier.classify(fix(0.0002, epoch = t0 + 9, speed = 4.0))

        assertFalse(decision.report, "cadence(max(0.5, 4)) = 10 s; 9 s in is filtered")
        assertTrue(decision.reason.contains("停留状态-高速点-上报间隔未超过最小时间间隔"))
    }

    @Test
    fun `leaving the stay radius at speed is a departure`() {
        enterStay()

        val decision = classifier.classify(fix(0.005, epoch = T0 + 1000, speed = 6.0)) // ~556 m

        assertTrue(decision.report)
        assertEquals(ReportKind.LEAVE, decision.kind)
        assertTrue(decision.reason.contains("认为是离开点"))
        assertNull(classifier.stayPoint)
    }

    @Test
    fun `walking far from a parked stay is also a departure and seeds a new potential stay`() {
        enterStay()

        val decision = classifier.classify(fix(0.005, epoch = T0 + 1000, speed = 1.0)) // ~556 m > 250 m

        assertTrue(decision.report)
        assertEquals(ReportKind.LEAVE, decision.kind)
        assertNull(classifier.stayPoint)
        assertNotNull(classifier.potentialStayPoint, "the departure point becomes the next candidate")
    }

    @Test
    fun `a slow drift 200-250 m out is a drift point, not a departure`() {
        enterStay()

        // ~267 m raw, minus the 25 m fix accuracy (the stay carries 0): ~242 m, inside (200, 250].
        val decision = classifier.classify(fix(0.0024, epoch = T0 + 1000, speed = 1.0))

        assertTrue(decision.report)
        assertEquals(ReportKind.UNCLASSIFIED, decision.kind, "the drift point goes out unclassified")
        assertTrue(decision.reason.contains("可能是漂点"))
        assertNotNull(classifier.stayPoint, "a drift does not end the stay")
        assertNull(classifier.potentialStayPoint, "and does not start one either")
    }

    // ------------------------------------------------------------------ widget copy kind (R())

    @Test
    fun `the widget copy of an unclassified fix inside a stay carries type 4`() {
        enterStay()
        assertEquals(ReportKind.UNCHANGED, classifier.widgetCopyKind(ReportKind.UNCLASSIFIED))
        assertEquals(ReportKind.STAY, classifier.widgetCopyKind(ReportKind.STAY), "classified fixes pass through")
        assertEquals(ReportKind.LEAVE, classifier.widgetCopyKind(ReportKind.LEAVE))
    }

    @Test
    fun `without a stay the widget copy passes through unchanged`() {
        assertEquals(ReportKind.UNCLASSIFIED, classifier.widgetCopyKind(ReportKind.UNCLASSIFIED))
    }

    // ------------------------------------------------------------------ 待验 62: the two readings

    @Test
    fun `the potential-exit radius is a one-line switch between the two readings`() {
        // 0.001 deg ≈ 111 m: within the shipping reading (200 m), beyond the key-name reading (20 m).
        val byName = StayLeaveClassifier(tuning, potentialExitMeters = tuning.potentialStayDistanceThresholdMeters)
        byName.classify(fix(0.0, speed = 1.0))
        val decision = byName.classify(fix(0.001, epoch = T0 + 100, speed = 1.0))

        assertTrue(decision.reason.contains("标记为新的潜在停留点"), "under the 20 m reading 111 m resets the candidate")
    }

    // ------------------------------------------------------------------ pace (intake walk adaptation)

    @Test
    fun `fast fixes ask for the two-second loop and reset the accumulator`() {
        assertEquals(2_000L, classifier.pace(fix(0.0, speed = 6.0)))
        assertEquals(2_000L, classifier.pace(fix(0.0, speed = 6.0)))
    }

    @Test
    fun `ninety consecutive slow fixes relax the loop once, then restart the count`() {
        repeat(tuning.lowSpeedTimesAsWalk - 1) { assertNull(classifier.pace(fix(0.0, speed = 1.0))) }
        assertEquals(
            tuning.lowSpeedLocIntervalMillis.toLong(),
            classifier.pace(fix(0.0, speed = 1.0)),
            "the ninetieth slow fix flips the loop to walk mode",
        )
        assertNull(classifier.pace(fix(0.0, speed = 1.0)), "and the counter restarts from zero")
        assertEquals(2_000L, classifier.pace(fix(0.0, speed = 6.0)), "a fast fix snaps back to the fast loop")
    }

    @Test
    fun `displacement over time stands in when the vendor reported no speed`() {
        assertNull(classifier.pace(fix(0.0, speed = 0.0, epoch = T0)), "no previous fix, no derived speed")
        assertEquals(
            2_000L,
            classifier.pace(fix(0.001, speed = 0.0, epoch = T0 + 10)),
            "111 m over 10 s ≈ 11 m/s: moving, by arithmetic",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        const val T0 = 1_700_000_000L
    }

    /**
     * Latitude offsets speak in fractions of a degree: `0.001` ≈ 111 m, `0.0001` ≈ 11 m at this latitude.
     * Offset fixes carry 25 m accuracy, the origin 0, matching which branches subtract it.
     */
    private fun fix(latOffsetDeg: Double, epoch: Long = T0, speed: Double): LocationFix =
        LocationFix.normalized(
            lat = 39.90469 + latOffsetDeg,
            lng = 116.40717,
            system = CoordSystem.WGS84,
            epochSeconds = epoch,
            accuracyMeters = if (latOffsetDeg == 0.0) 0f else 25f,
            speedMetersPerSecond = speed.toFloat(),
            providerId = "test",
        )
}
