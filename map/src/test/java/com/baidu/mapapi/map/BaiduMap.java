package com.baidu.mapapi.map;

import java.util.ArrayList;
import java.util.List;

/** Test double for the map controller: records overlays and camera moves instead of drawing. */
public class BaiduMap {

    public final List<OverlayOptions> added = new ArrayList<>();
    public final List<MapStatusUpdate> statuses = new ArrayList<>();
    public int clearCount;

    public Overlay addOverlay(OverlayOptions options) {
        added.add(options);
        return new Overlay(options);
    }

    public void clear() {
        clearCount++;
        added.clear();
    }

    public void setMapStatus(MapStatusUpdate update) {
        statuses.add(update);
    }
}
