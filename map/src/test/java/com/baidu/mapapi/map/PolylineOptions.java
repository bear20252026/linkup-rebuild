package com.baidu.mapapi.map;

import com.baidu.mapapi.model.LatLng;
import java.util.List;

/** Test double for the polyline builder; the adapter's fluent chain lands in public fields. */
public class PolylineOptions extends OverlayOptions {

    public List<LatLng> points;
    public int width;
    public int color;

    public PolylineOptions points(List<LatLng> points) {
        this.points = points;
        return this;
    }

    public PolylineOptions width(int width) {
        this.width = width;
        return this;
    }

    public PolylineOptions color(int color) {
        this.color = color;
        return this;
    }
}
