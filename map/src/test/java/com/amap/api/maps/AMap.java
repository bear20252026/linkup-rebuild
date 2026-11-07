package com.amap.api.maps;

import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the AMap map controller. `addOverlay` is typed to the two option classes the adapter
 * binds, so a test can read back what was drawn without a common base type.
 */
public class AMap {

    public final List<PolylineOptions> polylines = new ArrayList<>();
    public final List<MarkerOptions> markers = new ArrayList<>();
    public final List<CameraUpdate> cameraMoves = new ArrayList<>();
    public int clearCount;

    public void addOverlay(PolylineOptions options) {
        polylines.add(options);
    }

    public void addOverlay(MarkerOptions options) {
        markers.add(options);
    }

    public void clear() {
        clearCount++;
        polylines.clear();
        markers.clear();
    }

    public void moveCamera(CameraUpdate update) {
        cameraMoves.add(update);
    }
}
