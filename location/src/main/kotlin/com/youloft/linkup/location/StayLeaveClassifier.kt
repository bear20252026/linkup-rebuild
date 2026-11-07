package com.youloft.linkup.location

/**
 * The stay / leave judgment the recovered app ran on every queued fix, ported from
 * `_raw/out_jadx_156/sources/com/youloft/watcher/manager/x0.java:164-265` — the inner class `c` — rule by
 * rule, with its own truth table ([StayLeaveClassifierTest]) instead of the paraphrase the design doc once
 * refused to write.
 *
 * ## What one call decides
 *
 * [classify] answers two questions at once, which is how the original could run as a single gate in its
 * upload worker: *should this fix be reported at all* (the cadence comparisons inside are the same rule
 * [ReportGate] applies when no classifier is configured — with one difference: a parked device is capped at
 * [LocationTuning.stayMinTimeIntervalSeconds], not the moving cadence), and *why* (`kind`: a potential stay
 * maturing stamps [ReportKind.STAY], a judged departure stamps [ReportKind.LEAVE], everything else goes out
 * [ReportKind.UNCLASSIFIED]). The original wrote that answer into the bean's `type` field; here it is the
 * enum, and the caller's gateway puts the wire number on it.
 *
 * ## The three state variables are the original's
 *
 * `stayPoint` (`f64563h`), `potentialStayPoint` (`f64564i`) and `lastReported` (`f64565j`) — set exactly
 * where the original sets them: `lastReported` only after the gateway took a fix ([onReported], the
 * original's line 914), the stay point when a potential stay matures or a departure is judged.
 * [stayPoint] is exposed because the original persisted it (`CacheUtils.O()` restore, `c2()` save); the
 * service layer that hosts this classifier decides whether to do the same.
 *
 * ## Two deliberate deviations, both recorded
 *
 * - **Distance math runs on WGS84** ([distanceMeters], great-circle) where the original measured BD09 via
 *   `DistanceUtil`. The bean stores WGS84, so this is the same number in a different datum, not a new
 *   threshold.
 * - **The potential-stay-range exit uses [LocationTuning.stayDistanceThresholdMeters] (200 m)** — what the
 *   shipping binary reads at `x0.java:242` (`n()`), although the key's own name suggests
 *   `potentialStayDistanceThresholdMeters` (20 m, `k()`). The two readings differ by 10× and decide stay
 *   density on tracks; this is recovered 待验 62 and stays open for the backend or a raw-fix capture to
 *   settle. The reading is a constructor parameter, so switching is a one-line change plus a test.
 *
 * Not thread-safe, like the original's single-coroutine worker: calls must be serialized (the collector's
 * intake path is).
 */
class StayLeaveClassifier(
    private val tuning: LocationTuning = LocationTuning(),
    /**
     * 待验 62. Defaults to the shipping binary's reading (`n()`); pass
     * `tuning.potentialStayDistanceThresholdMeters` to take the key-name reading instead.
     */
    private val potentialExitMeters: Double = tuning.stayDistanceThresholdMeters,
) {
    /** The stay the device is currently parked at (`f64563h`); null means "moving / no stay judged". */
    var stayPoint: LocationFix? = null
        private set

    /** The accumulating candidate (`f64564i`), reset whenever the device leaves its range. */
    var potentialStayPoint: LocationFix? = null
        private set

    /** The last fix the gateway accepted (`f64565j`); cadence comparisons run against it. */
    var lastReported: LocationFix? = null
        private set

    /** The original's intake adaptation (`x0.java:316-330`): track consecutive low-speed fixes. */
    private var lowSpeedCounter: Int = 0
    private var lastSeen: LocationFix? = null

    /** What [classify] decided for one fix. */
    data class Decision(
        /** False = drop the fix without queueing (the original's `c()` returning false). */
        val report: Boolean,
        /** Why this fix is worth sending, stamped into the bean's `type` on the wire. */
        val kind: ReportKind,
        /** The original's own log line for this branch — the collector emits it through its event hook. */
        val reason: String,
        /**
         * Walk-mode suggestion from the intake pacer, or null for "leave the sampling interval alone".
         * Reported on every fix (intake happens before the gate in the original too).
         */
        val suggestIntervalMillis: Long? = null,
    )

    /**
     * Intake-time walk adaptation (`x0.java:316-330`): a fast fix resets the low-speed counter and asks
     * for the 2 s loop; after [LocationTuning.lowSpeedTimesAsWalk] consecutive slow ones the loop relaxes
     * to [LocationTuning.lowSpeedLocIntervalMillis] once, and the counter restarts. The effective speed
     * falls back to displacement over time when the vendor did not supply one — the original compared raw
     * displacement against the m/s threshold (`ext.j.g`), which reads as m/s only by accident; this port
     * does the division the comparison implies.
     */
    fun pace(fix: LocationFix): Long? {
        val effectiveSpeed = fix.speedMetersPerSecond.takeIf { it > 0f } ?: displacementSpeed(lastSeen, fix)
        lastSeen = fix
        return if (effectiveSpeed != null && effectiveSpeed >= tuning.lowSpeedThresholdMetersPerSecond) {
            lowSpeedCounter = 0
            FAST_INTERVAL_MILLIS
        } else {
            lowSpeedCounter += 1
            if (lowSpeedCounter >= tuning.lowSpeedTimesAsWalk) {
                lowSpeedCounter = 0
                tuning.lowSpeedLocIntervalMillis.toLong()
            } else {
                null
            }
        }
    }

    /** The gate itself — one call per fix, in arrival order. See the class doc for the truth table. */
    fun classify(fix: LocationFix): Decision {
        val staySpeed = tuning.staySpeedThresholdMetersPerSecond
        val current = stayPoint
        if (current != null) {
            val distance = distanceMeters(current.coord, fix.coord)
                ?.let { it - fix.accuracyMeters - current.accuracyMeters }
            if (distance != null && distance <= tuning.stayDistanceThresholdMeters) {
                if (fix.speedMetersPerSecond <= staySpeed) {
                    if (lastReported != null &&
                        fix.epochSeconds - requireNotNull(lastReported).epochSeconds < tuning.stayMinTimeIntervalSeconds
                    ) {
                        return Decision(false, ReportKind.UNCLASSIFIED, "过滤-停留状态-停留范围内-低速点-距离上次上报不超过10分钟")
                    }
                    return Decision(true, ReportKind.UNCLASSIFIED, "上传-停留状态-停留范围内-低速点-距离上次上报超过10分钟")
                }
                if (lastReported != null && !cadenceElapsed(fix)) {
                    return Decision(false, ReportKind.UNCLASSIFIED, "停留状态-高速点-上报间隔未超过最小时间间隔")
                }
                return Decision(true, ReportKind.UNCLASSIFIED, "上传-停留状态-停留范围内-高速点")
            }
            if (fix.speedMetersPerSecond > staySpeed) {
                stayPoint = null
                return Decision(true, ReportKind.LEAVE, "上传-停留状态-停留范围外-高速点-认为是离开点")
            }
            if (distance == null || distance > tuning.distanceToStayPointMeters) {
                stayPoint = null
                potentialStayPoint = fix
                return Decision(true, ReportKind.LEAVE, "上传-停留状态-停留范围外-低速点-距离超过${tuning.distanceToStayPointMeters}-认为是离开点")
            }
            return Decision(true, ReportKind.UNCLASSIFIED, "上传-停留状态-停留范围外-低速点，距离小于${tuning.distanceToStayPointMeters}，可能是漂点")
        }
        if (lastReported != null && !cadenceElapsed(fix)) {
            return Decision(false, ReportKind.UNCLASSIFIED, "过滤-非停留状态-上报间隔未超过最小时间间隔")
        }
        if (fix.speedMetersPerSecond > staySpeed) {
            return Decision(true, ReportKind.UNCLASSIFIED, "上传-非停留状态-高速点")
        }
        val potential = potentialStayPoint
        if (potential == null) {
            potentialStayPoint = fix
            return Decision(true, ReportKind.UNCLASSIFIED, "上传-非停留状态-低速点-无潜在停留点-标记为潜在停留点")
        }
        val distance = distanceMeters(fix.coord, potential.coord)
        if (distance != null && distance > potentialExitMeters) {
            potentialStayPoint = fix
            return Decision(true, ReportKind.UNCLASSIFIED, "上传-非停留状态-低速点-潜在停留范围外-标记为新的潜在停留点")
        }
        if (fix.epochSeconds - potential.epochSeconds > tuning.stayThresholdSeconds) {
            stayPoint = potential
            potentialStayPoint = null
            return Decision(true, ReportKind.STAY, "上传-非停留状态-低速点-潜在停留范围内-潜在停留超过${tuning.stayThresholdSeconds}秒-停留状态")
        }
        if (distance != null && distance < tuning.potentialStayDistanceThresholdMeters) {
            return Decision(false, ReportKind.UNCLASSIFIED, "过滤-非停留状态-低速点-潜在停留范围内-潜在停留未超过${tuning.stayThresholdSeconds}秒-距离潜在停留点未超过${tuning.potentialStayDistanceThresholdMeters}米")
        }
        return Decision(true, ReportKind.UNCLASSIFIED, "上传-非停留状态-低速点-潜在停留范围内-潜在停留未超过${tuning.stayThresholdSeconds}秒-距离潜在停留点超过${tuning.potentialStayDistanceThresholdMeters}米")
    }

    /** Called exactly when the gateway accepted a fix — the original's `f64565j = location` (line 914). */
    fun onReported(fix: LocationFix) {
        lastReported = fix
    }

    /** Whether the widget copy of a just-reported fix should carry the type-4 "unchanged" marker (`R()`). */
    fun widgetCopyKind(kind: ReportKind): ReportKind =
        if (stayPoint != null && kind == ReportKind.UNCLASSIFIED) ReportKind.UNCHANGED else kind

    private fun cadenceElapsed(fix: LocationFix): Boolean {
        val previous = requireNotNull(lastReported)
        val cadence = tuning.cadenceSeconds(maxOf(previous.speedMetersPerSecond, fix.speedMetersPerSecond))
        return fix.epochSeconds - previous.epochSeconds >= cadence
    }

    private fun displacementSpeed(previous: LocationFix?, fix: LocationFix): Float? {
        if (previous == null) return null
        val dt = fix.epochSeconds - previous.epochSeconds
        if (dt <= 0) return null
        val metres = distanceMeters(previous.coord, fix.coord) ?: return null
        return (metres / dt).toFloat()
    }

    private companion object {
        /** The original rewrote `scanSpan = 2000` on every fast fix (`x0.java:322-326`). */
        const val FAST_INTERVAL_MILLIS = 2_000L
    }
}
