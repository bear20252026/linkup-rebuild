package com.amap.api.maps.model;

/** Test double for the AMap marker builder. */
public class MarkerOptions {

    public LatLng position;

    public MarkerOptions position(LatLng position) {
        this.position = position;
        return this;
    }
}
