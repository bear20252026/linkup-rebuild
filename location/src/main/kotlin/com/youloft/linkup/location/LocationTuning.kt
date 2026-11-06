package com.youloft.linkup.location

/**
 * The numeric thresholds that decide how often a fix is worth sending.
 *
 * Defaults are the recovered app's own, taken from `manager/l.java:64-80` — that class is a straight
 * transcription of `ConfigUtils.d(key, default)` calls, so the key names and the fallbacks below are what
 * shipped, and the remote config service can still override every one of them. The full seventeen-key table
 * (including the stay/potential-stay thresholds this round does not yet use) is recorded in
 * `recovered/DESIGN_CN.md` §五点二十六; adding a field here when a rule needs it is intentional, so no
 * number is ever a magic literal at its call site.
 */
data class LocationTuning(
    /** `locationScanMillsWhenDormancy` — interval used while the app is backgrounded. */
    val dormancyIntervalMillis: Long = 30_000,

    /** `locationMinInterval` — the cadence floor, in seconds. */
    val minIntervalSeconds: Long = 5,

    /** `locationMaxInterval` — the cadence ceiling, in seconds, and the answer when the device is stopped. */
    val maxIntervalSeconds: Long = 20,

    /** `locationMinUploadDistance` — the metres-per-report budget the cadence divides by. */
    val minUploadDistanceMeters: Double = 40.0,

    /** How many unsent fixes to keep. The original's queue was unbounded; this one is not, on purpose. */
    val pendingLimit: Int = 64,
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
            )
        }

        /** Key names verbatim from `manager/l.java:64-80`. */
        const val KEY_DORMANCY_INTERVAL_MILLIS = "locationScanMillsWhenDormancy"
        const val KEY_MIN_INTERVAL_SECONDS = "locationMinInterval"
        const val KEY_MAX_INTERVAL_SECONDS = "locationMaxInterval"
        const val KEY_MIN_UPLOAD_DISTANCE_METERS = "locationMinUploadDistance"
    }
}
