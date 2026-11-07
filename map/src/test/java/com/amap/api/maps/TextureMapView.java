package com.amap.api.maps;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

/** Test double for the AMap map view — onCreate takes only the state bundle, unlike Baidu's. */
public class TextureMapView extends View {

    public int createCount;
    public int resumeCount;
    public int pauseCount;
    public int destroyCount;
    public int saveStateCount;
    public Bundle lastCreateState;

    private final AMap map = new AMap();

    public TextureMapView(Context context) {
        super(context);
    }

    public void onCreate(Bundle savedInstanceState) {
        createCount++;
        lastCreateState = savedInstanceState;
    }

    public void onResume() {
        resumeCount++;
    }

    public void onPause() {
        pauseCount++;
    }

    public void onDestroy() {
        destroyCount++;
    }

    public Bundle onSaveInstanceState() {
        saveStateCount++;
        return new Bundle();
    }

    public AMap getMap() {
        return map;
    }
}
