package com.baidu.mapapi.map;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

/** Test double for the map view, shaped from the official SDK's lifecycle contract. */
public class TextureMapView extends View {

    public int createCount;
    public int resumeCount;
    public int pauseCount;
    public int destroyCount;
    public int saveStateCount;
    public Bundle lastCreateState;

    private final BaiduMap map = new BaiduMap();

    public TextureMapView(Context context) {
        super(context);
    }

    public void onCreate(Context context, Bundle savedInstanceState) {
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

    public BaiduMap getMap() {
        return map;
    }
}
