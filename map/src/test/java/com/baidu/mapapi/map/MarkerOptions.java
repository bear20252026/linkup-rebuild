package com.baidu.mapapi.map;

import com.baidu.mapapi.model.LatLng;

/** Test double for the marker builder. */
public class MarkerOptions extends OverlayOptions {

    public LatLng position;

    public MarkerOptions position(LatLng position) {
        this.position = position;
        return this;
    }
}
