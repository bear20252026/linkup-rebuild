package com.amap.api.location;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the AMap option object, carrying the four setters `AmapSdk.buildOption` binds, plus the
 * nested `AMapLocationMode` enum whose binary name (`...$AMapLocationMode`, exactly one `$`) is what the
 * reflective enum lookup must find.
 *
 * Defaults follow the official SDK documentation: mode `Hight_Accuracy`, interval 2000 ms,
 * needAddress true, onceLocation false. Every setter records itself in {@link #calls} so a test can prove
 * the setter ran with a non-null value rather than the field happening to hold the default.
 */
public class AMapLocationClientOption {

    /** Official SDK's nested mode enum; the misspelling "Hight" is AMap's own and is load-bearing. */
    public enum AMapLocationMode {
        Hight_Accuracy,
        Device_Sensors,
        Battery_Saving,
    }

    public final List<String> calls = new ArrayList<>();

    public AMapLocationMode mode = AMapLocationMode.Hight_Accuracy;
    public long interval = 2000;
    public boolean needAddress = true;
    public boolean onceLocation = false;

    public void setLocationMode(AMapLocationMode mode) {
        this.mode = mode;
        calls.add("setLocationMode:" + mode);
    }

    public void setInterval(long interval) {
        this.interval = interval;
        calls.add("setInterval:" + interval);
    }

    public void setNeedAddress(boolean needAddress) {
        this.needAddress = needAddress;
        calls.add("setNeedAddress:" + needAddress);
    }

    public void setOnceLocation(boolean onceLocation) {
        this.onceLocation = onceLocation;
        calls.add("setOnceLocation:" + onceLocation);
    }
}
