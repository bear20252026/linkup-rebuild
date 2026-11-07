package com.baidu.mapapi.map;

import com.baidu.mapapi.model.LatLng;

/** Test double for the camera update the factory produces. */
public class MapStatusUpdate {

    public final LatLng target;
    public final float zoom;

    public MapStatusUpdate(LatLng target, float zoom) {
        this.target = target;
        this.zoom = zoom;
    }
}
