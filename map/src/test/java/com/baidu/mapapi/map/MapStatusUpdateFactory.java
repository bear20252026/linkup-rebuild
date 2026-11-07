package com.baidu.mapapi.map;

import com.baidu.mapapi.model.LatLng;

/** Test double for the camera factory. */
public class MapStatusUpdateFactory {

    public static MapStatusUpdate newLatLngZoom(LatLng target, float zoom) {
        return new MapStatusUpdate(target, zoom);
    }
}
