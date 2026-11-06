package com.youloft.watcher.data

import com.youloft.linkup.api.dto.Address
import com.youloft.linkup.api.dto.Location
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationGateway
import com.youloft.linkup.location.ReportKind
import com.youloft.linkup.net.LinkUpApi

/**
 * The production [LocationGateway]: the recovered `POST api/location/report`, with the body being the
 * `Location` **bean** rather than a map (`gh/a.java:401` passed the bean straight to Retrofit), so the ten
 * keys below are the wire format and nothing in this layer gets to rename them.
 *
 * The coordinate goes out exactly as the neutral model holds it — WGS84 — which is what the original sent
 * too: `manager/x0.java:290` converted Baidu's bd09ll to WGS84 *before* building the bean, and
 * `bean/Location.java:222 getLatLng09()` converted back only for local distance maths. A gateway that sent
 * the raw vendor datum would be accepted by the server and then draw every track ~500 m away from the
 * address it reports.
 *
 * A non-200 `status` is thrown rather than returned, because [LocationGateway.report] has no failure value
 * by design: the recovered client only looked at `status` and toasted `msg`, so a caller that cares (the
 * collector, which keeps the fix for retry) is the one that has to see it.
 */
class LinkUpLocationGateway(private val api: LinkUpApi) : LocationGateway {
    override suspend fun report(fix: LocationFix, kind: ReportKind) {
        val response = api.locationReport(fix.toWireLocation(kind))
        if (!response.isSuccess) throw LocationReportException(response.status, response.msg)
    }
}

/** Server refused a report; [status] is the envelope code, 1001 参数异常 and 403 无权限 are the two seen. */
class LocationReportException(val status: Int, override val message: String?) : RuntimeException(message)

/**
 * Bean mapping kept in one place so the key set can be asserted. `id` is the client's Room primary key and
 * `imPush` is its stay counter — both are sent (as 0 / absent) because the bean declares them, and the
 * rebuilt server ignores both (`server/internal/location/location.go:41-51` decodes nine keys).
 */
fun LocationFix.toWireLocation(kind: ReportKind): Location = Location(
    accuracy = accuracyMeters,
    address = place?.let {
        Address(
            province = it.province,
            city = it.city,
            district = it.district,
            street = it.street,
            streetNumber = it.streetNumber,
            poiName = it.poiName,
        )
    },
    id = 0L,
    imPush = null,
    lat = coord.lat,
    lng = coord.lng,
    locType = vendorFixType ?: 0,
    speed = speedMetersPerSecond,
    timePeriod = epochSeconds,
    type = kind.wireType,
)
