package com.youloft.linkup.location

/**
 * A human-readable location, in exactly the six fields the recovered wire format carries.
 *
 * `com/youloft/common/bean/Address.java` has province/city/district/street/streetNumber/poiName and
 * **no full-address field** — the app never stored a composed string, the server composes it
 * (`server/internal/location/placeOf`). Keeping the split here is what lets a Baidu fix and a
 * `Geocoder` fix be interchangeable: they only have to agree on these six.
 *
 * `poiName` is whatever the SDK calls the nearest point of interest. Baidu fills it from the first entry
 * of `BDLocation.getPoiList()` and uses `""` when the list is empty (`manager/x0.java:299-304`); the
 * framework `Geocoder` has no POI concept at all, so its adapter leaves it null instead of inventing one.
 */
data class Place(
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val street: String? = null,
    val streetNumber: String? = null,
    val poiName: String? = null,
) {
    /**
     * The recovered app compares `location.address == null`, not "blank", before deciding to reverse
     * geocode a fix (`manager/x0.java:785`). This keeps that branch honest when an SDK answers with six
     * empty strings instead of null.
     */
    val isBlank: Boolean
        get() = listOf(province, city, district, street, streetNumber, poiName).all { it.isNullOrEmpty() }
}
