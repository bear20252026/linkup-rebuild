package com.youloft.watcher.pages.track

import com.youloft.linkup.api.dto.TrackRecordBean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The geometry feed for the map slot: path verbatim, stay pins from the type-2 arrival rows only. */
class TrackOverlayMapperTest {

    @Test
    fun `the path is the points array and stays are the type-2 track rows`() {
        val record = TrackRecordBean(
            points = listOf(
                TrackRecordBean.Point(39.90469, 116.40717, timePeriod = 1_700_000_000),
                TrackRecordBean.Point(39.90569, 116.40817, timePeriod = 1_700_000_020),
            ),
            tracks = listOf(
                TrackRecordBean.Track(lat = 39.90469, lng = 116.40717, type = 2, timePeriod = 1_700_000_100),
                TrackRecordBean.Track(lat = 39.90569, lng = 116.40817, type = 3, timePeriod = 1_700_000_200),
            ),
        )

        val overlay = TrackOverlayMapper.from(record)

        assertEquals(listOf(39.90469, 39.90569), overlay.path.map { it.lat })
        assertEquals(
            listOf(1_700_000_100L),
            overlay.stays.map { it.timePeriod },
            "type 3 is a departure row; only the arrival marker draws a pin",
        )
    }

    @Test
    fun `nulls filter instead of crash and a null record is an empty overlay`() {
        val overlay = TrackOverlayMapper.from(
            TrackRecordBean(
                points = listOf(TrackRecordBean.Point(39.9, 116.4), null),
                tracks = listOf(null, TrackRecordBean.Track(type = 2)),
            ),
        )
        assertEquals(1, overlay.path.size)
        assertEquals(1, overlay.stays.size)

        assertTrue(TrackOverlayMapper.from(null).path.isEmpty())
        assertTrue(TrackOverlayMapper.from(null).stays.isEmpty())
    }
}
