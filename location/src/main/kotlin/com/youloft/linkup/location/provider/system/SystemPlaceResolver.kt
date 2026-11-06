package com.youloft.linkup.location.provider.system

import android.content.Context
import android.location.Geocoder
import com.youloft.linkup.location.Coord
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.Place
import com.youloft.linkup.location.PlaceResolver

/**
 * Address resolution over the platform `Geocoder` — the fallback the recovered app did not have, and the
 * reason [PlaceResolver] is a separate seam rather than a method on a provider.
 *
 * The original always had an address because Baidu resolved it inside the location SDK
 * (`setIsNeedAddress(true)`, `manager/x0.java:689`) and reached for `GeoCoder` only when a fix arrived
 * without one (`x0.java:785-797`). A framework-only build has the opposite ratio: every fix needs a
 * resolution, so this implementation exists to keep the same six fields populated.
 *
 * What it cannot do is invent a POI: `Place.poiName` stays null, because `Address` has no full-address
 * field and the server composes the display string from these six
 * (`server/internal/location/placeOf`) — a made-up POI would land in the user's track list.
 *
 * The `Geocoder` call is blocking, so it runs on whatever dispatcher the caller chose; both directions
 * answer null rather than throwing, matching `utils/j0.java:37,67`, which treated an empty or zero result as
 * "no answer".
 */
class SystemPlaceResolver(context: Context) : PlaceResolver {
    private val appContext = context.applicationContext
    private val geocoder by lazy { Geocoder(appContext) }

    override val isAvailable: Boolean
        get() = Geocoder.isPresent()

    override suspend fun resolvePlace(coord: Coord): Place? {
        if (!isAvailable) return null
        val wgs = coord.asWgs84()
        val matches = runCatching {
            geocoder.getFromLocation(wgs.lat, wgs.lng, 1).orEmpty()
        }.getOrNull().orEmpty()
        val match = matches.firstOrNull() ?: return null
        val place = Place(
            province = match.adminArea,
            city = match.locality ?: match.adminArea,
            district = match.subLocality,
            street = match.thoroughfare,
            streetNumber = match.subThoroughfare,
        )
        // j0.java:67 refuses a result whose address string is empty; the same refusal keeps a "resolved"
        // nothing from being reported as a place.
        return place.takeUnless { it.isBlank }
    }

    override suspend fun resolveCoord(city: String, address: String): Coord? {
        if (!isAvailable) return null
        val query = listOf(city, address).filter { it.isNotBlank() }.joinToString("")
        if (query.isBlank()) return null
        val found = runCatching {
            geocoder.getFromLocationName(query, 1).orEmpty()
        }.getOrNull().orEmpty().firstOrNull() ?: return null
        // `j0.java:37` rejects a geocode result at (0,0); `Locale` is what makes Chinese queries work at all.
        if (found.latitude == 0.0 && found.longitude == 0.0) return null
        return Coord(found.latitude, found.longitude, CoordSystem.WGS84)
    }
}
