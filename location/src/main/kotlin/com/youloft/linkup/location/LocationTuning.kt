package com.youloft.linkup.location

/**
 * The numeric thresholds that decide how often a fix is worth sending and what it means.
 *
 * Defaults are the recovered app's own, transcribed field by field from the `l()` constructor in
 * `_raw/out_jadx_156/sources/com/youloft/watcher/manager/l.java:64-80` — that class is a straight list of
 * `ConfigUtils.d(key, default)` calls, so the key names and the fallbacks below are what shipped, and the
 * remote config service can still override every one of them. No number here is a magic literal at its
 * call site; [StayLeaveClassifier] and [ReportGate] read them from this table.
 */
data class LocationTuning(
    // ---- reporting cadence (the five this layer needed first) -------------------------------------

    /** `locationScanMillsWhenDormancy` — interval used while the app is backgrounded (`d()`). */
    val dormancyIntervalMillis: Long = 30_000,

    /** `locationMinInterval` — the cadence floor, in seconds (`i()`). */
    val minIntervalSeconds: Long = 5,

    /** `locationMaxInterval` — the cadence ceiling, in seconds, and the answer when the device is stopped (`h()`). */
    val maxIntervalSeconds: Long = 20,

    /** `locationMinUploadDistance` — the metres-per-report budget the cadence divides by (`c()`). */
    val minUploadDistanceMeters: Double = 40.0,

    /** How many unsent fixes to keep. The original's queue was unbounded; this one is not, on purpose. */
    val pendingLimit: Int = 64,

    // ---- stay / leave judgment (the twelve the classifier reads) ------------------------------------

    /** `locationStayThreshold` — how long a potential stay point must persist to mature into a stay, in seconds (`q()`). */
    val stayThresholdSeconds: Long = 300,

    /** `locationStayMinTimeInterval` — minimum spacing between reports while parked at a stay, in seconds (`o()`). */
    val stayMinTimeIntervalSeconds: Long = 900,

    /** `potentialStayDistanceThreshold` — radius of the potential-stay accumulation zone, in metres (`k()`). */
    val potentialStayDistanceThresholdMeters: Double = 20.0,

    /** `stayDistanceThreshold` — radius inside which the device still counts as being at the stay, in metres (`n()`). */
    val stayDistanceThresholdMeters: Double = 200.0,

    /** `staySpeedThreshold` — below this speed a fix is "low speed" for the stay logic, in m/s (`p()`). */
    val staySpeedThresholdMetersPerSecond: Double = 1.5,

    /** `distanceToStayPoint` — beyond this from a potential stay the device has drifted, in metres (`b()`). */
    val distanceToStayPointMeters: Double = 250.0,

    /** `sendCmdLocationDistanceThreshold` — within this of the last reported fix, reuse its address, in metres (`m()`). */
    val sendCmdLocationDistanceThresholdMeters: Double = 10.0,

    /** `autoReportStayTimeThreshold` — parked this long at a stay fires the duration report, in seconds (`a()`). */
    val autoReportStayTimeThresholdSeconds: Long = 7200,

    /** `requestTimeWhenNoLoc` — queue silence this long restarts sampling, in seconds (`l()`). */
    val requestTimeWhenNoLocSeconds: Long = 600,

    /** `lowSpeedThreshold` — below this the walk-mode accumulator advances, in m/s (`f()`). */
    val lowSpeedThresholdMetersPerSecond: Double = 5.0,

    /** `lowSpeedTimesAsWalk` — consecutive low-speed fixes before the interval relaxes to walk mode (`g()`). */
    val lowSpeedTimesAsWalk: Int = 90,

    /** `lowSpeedLocInterval` — the relaxed walk-mode sampling interval, in milliseconds (`e()`). */
    val lowSpeedLocIntervalMillis: Int = 5_000,

    /** `movingTrackDelay` — spacing enforced between reports of cached fixes while moving, in seconds (`j()`). */
    val movingTrackDelaySeconds: Long = 180,
) {
    /**
     * A literal port of `manager/x0.java:156-158`:
     *
     * `speed <= 0 -> maxInterval` otherwise `min(max(minUploadDistance / speed, minInterval), maxInterval)`
     *
     * so a stopped device reports every 20 s, and a moving one keeps the *distance* between reports near
     * 40 m until the 5 s floor. Speed is m/s here, which matters: Baidu reports km/h and the app divided by
     * 3.6 before storing it (`x0.java:288`), so feeding raw vendor speed into this formula would silently
     * make the app 3.6× stingier.
     */
    fun cadenceSeconds(speedMetersPerSecond: Float): Double =
        if (speedMetersPerSecond <= 0f) maxIntervalSeconds.toDouble()
        else minOf(
            maxOf(minUploadDistanceMeters / speedMetersPerSecond.toDouble(), minIntervalSeconds.toDouble()),
            maxIntervalSeconds.toDouble(),
        )

    /** `s0.java:810` for the mode, `locationScanMillsWhenDormancy` for the interval. */
    fun dormantRequest() = LocationRequest.dormant(dormancyIntervalMillis)

    fun followingRequest() = LocationRequest.FOREGROUND

    companion object {
        /**
         * Overrides the defaults with whatever the config service actually carries, key by key, and keeps
         * the recovered fallback for any key it does not. The reader is a plain function rather than a
         * config type because this module must not learn how the app stores remote values — see the same
         * shape in `manager/l.java:64-80`, which read `ConfigUtils.d(key, default)` once per field.
         */
        fun fromConfig(read: (String) -> Double?): LocationTuning {
            val defaults = LocationTuning()
            return LocationTuning(
                dormancyIntervalMillis = read(KEY_DORMANCY_INTERVAL_MILLIS)?.toLong() ?: defaults.dormancyIntervalMillis,
                minIntervalSeconds = read(KEY_MIN_INTERVAL_SECONDS)?.toLong() ?: defaults.minIntervalSeconds,
                maxIntervalSeconds = read(KEY_MAX_INTERVAL_SECONDS)?.toLong() ?: defaults.maxIntervalSeconds,
                minUploadDistanceMeters = read(KEY_MIN_UPLOAD_DISTANCE_METERS) ?: defaults.minUploadDistanceMeters,
                stayThresholdSeconds = read(KEY_STAY_THRESHOLD)?.toLong() ?: defaults.stayThresholdSeconds,
                stayMinTimeIntervalSeconds = read(KEY_STAY_MIN_TIME_INTERVAL)?.toLong()
                    ?: defaults.stayMinTimeIntervalSeconds,
                potentialStayDistanceThresholdMeters = read(KEY_POTENTIAL_STAY_DISTANCE_THRESHOLD)
                    ?: defaults.potentialStayDistanceThresholdMeters,
                stayDistanceThresholdMeters = read(KEY_STAY_DISTANCE_THRESHOLD) ?: defaults.stayDistanceThresholdMeters,
                staySpeedThresholdMetersPerSecond = read(KEY_STAY_SPEED_THRESHOLD)
                    ?: defaults.staySpeedThresholdMetersPerSecond,
                distanceToStayPointMeters = read(KEY_DISTANCE_TO_STAY_POINT) ?: defaults.distanceToStayPointMeters,
                sendCmdLocationDistanceThresholdMeters = read(KEY_SEND_CMD_LOCATION_DISTANCE_THRESHOLD)
                    ?: defaults.sendCmdLocationDistanceThresholdMeters,
                autoReportStayTimeThresholdSeconds = read(KEY_AUTO_REPORT_STAY_TIME_THRESHOLD)?.toLong()
                    ?: defaults.autoReportStayTimeThresholdSeconds,
                requestTimeWhenNoLocSeconds = read(KEY_REQUEST_TIME_WHEN_NO_LOC)?.toLong()
                    ?: defaults.requestTimeWhenNoLocSeconds,
                lowSpeedThresholdMetersPerSecond = read(KEY_LOW_SPEED_THRESHOLD)
                    ?: defaults.lowSpeedThresholdMetersPerSecond,
                lowSpeedTimesAsWalk = read(KEY_LOW_SPEED_TIMES_AS_WALK)?.toInt() ?: defaults.lowSpeedTimesAsWalk,
                lowSpeedLocIntervalMillis = read(KEY_LOW_SPEED_LOC_INTERVAL)?.toInt()
                    ?: defaults.lowSpeedLocIntervalMillis,
                movingTrackDelaySeconds = read(KEY_MOVING_TRACK_DELAY)?.toLong() ?: defaults.movingTrackDelaySeconds,
            )
        }

        /** Key names verbatim from `manager/l.java:64-80`, in the constructor's order. */
        const val KEY_DORMANCY_INTERVAL_MILLIS = "locationScanMillsWhenDormancy"
        const val KEY_STAY_THRESHOLD = "locationStayThreshold"
        const val KEY_STAY_MIN_TIME_INTERVAL = "locationStayMinTimeInterval"
        const val KEY_MIN_INTERVAL_SECONDS = "locationMinInterval"
        const val KEY_MAX_INTERVAL_SECONDS = "locationMaxInterval"
        const val KEY_MIN_UPLOAD_DISTANCE_METERS = "locationMinUploadDistance"
        const val KEY_POTENTIAL_STAY_DISTANCE_THRESHOLD = "potentialStayDistanceThreshold"
        const val KEY_STAY_DISTANCE_THRESHOLD = "stayDistanceThreshold"
        const val KEY_STAY_SPEED_THRESHOLD = "staySpeedThreshold"
        const val KEY_DISTANCE_TO_STAY_POINT = "distanceToStayPoint"
        const val KEY_SEND_CMD_LOCATION_DISTANCE_THRESHOLD = "sendCmdLocationDistanceThreshold"
        const val KEY_AUTO_REPORT_STAY_TIME_THRESHOLD = "autoReportStayTimeThreshold"
        const val KEY_REQUEST_TIME_WHEN_NO_LOC = "requestTimeWhenNoLoc"
        const val KEY_LOW_SPEED_THRESHOLD = "lowSpeedThreshold"
        const val KEY_LOW_SPEED_TIMES_AS_WALK = "lowSpeedTimesAsWalk"
        const val KEY_LOW_SPEED_LOC_INTERVAL = "lowSpeedLocInterval"
        const val KEY_MOVING_TRACK_DELAY = "movingTrackDelay"
    }
}
