package com.amap.api.maps.model;

import java.util.ArrayList;
import java.util.List;

/** Test double for the AMap polyline builder: AMap accumulates points with `add`, width is a dp float. */
public class PolylineOptions {

    public final List<LatLng> points = new ArrayList<>();
    public float width;
    public int color;

    public PolylineOptions add(LatLng point) {
        points.add(point);
        return this;
    }

    public PolylineOptions width(float width) {
        this.width = width;
        return this;
    }

    public PolylineOptions color(int color) {
        this.color = color;
        return this;
    }
}
