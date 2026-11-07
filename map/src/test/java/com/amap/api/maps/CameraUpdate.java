package com.amap.api.maps;

import com.amap.api.maps.model.LatLng;

/** Test double for the camera update the factory produces. */
public class CameraUpdate {

    public final LatLng target;
    public final float zoom;

    public CameraUpdate(LatLng target, float zoom) {
        this.target = target;
        this.zoom = zoom;
    }
}
