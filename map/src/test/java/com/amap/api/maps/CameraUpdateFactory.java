package com.amap.api.maps;

import com.amap.api.maps.model.LatLng;

/** Test double for the AMap camera factory. */
public class CameraUpdateFactory {

    public static CameraUpdate newLatLngZoom(LatLng target, float zoom) {
        return new CameraUpdate(target, zoom);
    }
}
