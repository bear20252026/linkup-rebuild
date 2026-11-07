package com.youloft.watcher.pages.track

import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.linkup.map.MapLatLng
import com.youloft.linkup.map.StayMark
import com.youloft.linkup.map.TrackOverlay

/**
 * The raw record → map overlay, a pure function so the track page can keep its rendering decisions in the
 * adapter and its geometry decisions here.
 *
 * Two recovered facts drive the filtering: the walked path is the `points` array verbatim (`x0.java`
 * uploaded them, the server echoes them back), and the stay pins are the `tracks` rows whose `type == 2`
 * — the arrival marker the server reads out of the classifier's stamp (`server/internal/location` treats
 * type 2/3 as the arrival/departure pair; only arrivals draw a pin). Rows with `lostType != 0` keep
 * drawing: the list styles them differently, but the geometry still happened.
 */
object TrackOverlayMapper {

    fun from(record: TrackRecordBean?): TrackOverlay = TrackOverlay(
        path = record?.points.orEmpty().filterNotNull().map { MapLatLng(it.lat, it.lng) },
        stays = record?.tracks.orEmpty()
            .filterNotNull()
            .filter { it.type == STAY_TYPE }
            .map { StayMark(MapLatLng(it.lat, it.lng), it.timePeriod) },
    )

    private const val STAY_TYPE = 2
}
