package com.youloft.watcher.pages.track

import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.watcher.R

sealed class TrackRow {
    data class Summary(
        val stayCount: Int,
        val stayDurationMillis: Long,
        val distanceMeters: Double,
        val vipBadgeVisible: Boolean,
    ) : TrackRow()

    data class Point(val view: TrackPointView) : TrackRow()
}

data class TrackPointView(
    val time: String,
    val point: PointVisual,
    val line: LineVisual?,
    val selectable: Boolean,
    val track: TrackRecordBean.Track,
)

enum class PointIcon { STAY, LEAVE, RECORDING, PLAIN, LOST_RED }

data class PointVisual(
    val icon: PointIcon,
    val labelRes: Int?,
    val address: String?,
) {
    companion object {
        // A blank label remains visible, unlike a null (hidden) label.
        const val BLANK_LABEL = 0
    }
}

enum class LineKind { STAY, MOVE, NO_LOCATION, NO_NEW_LOCATION, UNKNOWN }

enum class SpeedIcon { WALK, BICYCLE, CAR, TRAIN, PLANE }

data class LineVisual(
    val kind: LineKind,
    val text: String,
    val opensReasonDialog: Boolean = false,
    val speed: SpeedIcon? = null,
)

class TrackRowBuilder(private val formatter: TrackFormatter) {

    fun build(record: TrackRecordBean): List<TrackRow> {
        val rows = mutableListOf<TrackRow>(
            TrackRow.Summary(
                stayCount = record.arrivalPointCount,
                stayDurationMillis = record.totalStayTime.toLong() * 1000L,
                distanceMeters = record.totalDistance,
                vipBadgeVisible = false,
            ),
        )
        rows += record.tracks.orEmpty().filterNotNull()
            .sortedByDescending { it.timePeriod }
            .map { TrackRow.Point(row(it)) }
        return rows
    }

    fun row(track: TrackRecordBean.Track): TrackPointView {
        val line = track.line
        var point = pointVisual(track)
        // Line 5 overrides even the address exposed by lostType 2.
        if (line?.type == 5) point = point.copy(address = null)
        return TrackPointView(
            time = formatter.clock(track.timePeriod),
            point = point,
            line = lineVisual(line),
            selectable = line?.type == 1 || line?.type == 2,
            track = track,
        )
    }

    private fun pointVisual(track: TrackRecordBean.Track): PointVisual {
        val address = track.address
        return when (track.lostType) {
            0 -> when (track.type) {
                2 -> PointVisual(PointIcon.STAY, R.string.arrive, address)
                3 -> PointVisual(PointIcon.LEAVE, R.string.leave, address = null)
                4 -> PointVisual(PointIcon.RECORDING, R.string.recording, address = null)
                else -> PointVisual(PointIcon.PLAIN, PointVisual.BLANK_LABEL, address = null)
            }
            1, 3 -> PointVisual(PointIcon.LOST_RED, labelRes = null, address = null)
            2 -> PointVisual(PointIcon.PLAIN, labelRes = null, address = address)
            // The original omitted unknown lost types; reset neutrally rather than inherit recycled content.
            else -> PointVisual(PointIcon.PLAIN, labelRes = null, address = null)
        }
    }

    private fun lineVisual(line: TrackRecordBean.Line?): LineVisual? {
        if (line == null) return null
        val durationMillis = line.duration * 1000L
        return when (line.type) {
            1 -> LineVisual(LineKind.STAY, stayBubble(durationMillis))
            2 -> LineVisual(
                LineKind.MOVE,
                formatter.moveBubble(line.distance, durationMillis),
                speed = speedIcon(line.distance, line.duration),
            )
            3 -> LineVisual(LineKind.NO_LOCATION, formatter.string(R.string.ta_disable_loc_always_unable_get_loc))
            4 -> LineVisual(
                LineKind.NO_NEW_LOCATION,
                formatter.string(R.string.no_new_loc),
                opensReasonDialog = true,
            )
            5 -> LineVisual(LineKind.UNKNOWN, stayBubble(durationMillis))
            else -> null
        }
    }

    private fun stayBubble(durationMillis: Long): String {
        val text = formatter.duration(durationMillis)
        return if (text.isEmpty()) formatter.string(R.string.less_than_1m) else text
    }

    companion object {
        // The original promotes 3.6f to Double, which matters immediately below icon thresholds.
        fun speedOf(distanceMeters: Double, durationSeconds: Long): Double =
            distanceMeters * 3.6f.toDouble() / durationSeconds

        fun speedIcon(distanceMeters: Double, durationSeconds: Long): SpeedIcon =
            speedIconOf(speedOf(distanceMeters, durationSeconds))

        // Preserve the original NaN fall-through to CAR (zero distance and zero duration).
        fun speedIconOf(kmh: Double): SpeedIcon = when {
            kmh < 10.0 -> SpeedIcon.WALK
            kmh >= 10.0 && kmh < 25.0 -> SpeedIcon.BICYCLE
            kmh < 25.0 || kmh >= 120.0 -> if (kmh < 120.0 || kmh >= 350.0) SpeedIcon.PLANE else SpeedIcon.TRAIN
            else -> SpeedIcon.CAR
        }
    }
}
