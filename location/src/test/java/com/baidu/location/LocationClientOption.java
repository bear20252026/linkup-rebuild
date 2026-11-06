package com.baidu.location;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for `LocationClientOption`, with the same public field names and the same setter names as
 * `_raw/out_jadx_156/sources/com/baidu/location/LocationClientOption.java` — the adapter writes both, so
 * both are recorded. `setIsNeedAddress` also rewrites `addrType` here exactly as the real class does at
 * `LocationClientOption.java:334-336`, because that is what makes `addrType` observable in a test.
 */
public class LocationClientOption {

    public enum LocationMode {
        Hight_Accuracy,
        Battery_Saving,
        Device_Sensors,
        Fuzzy_Locating
    }

    public enum FirstLocType {
        SPEED_IN_FIRST_LOC,
        ACCURACY_IN_FIRST_LOC
    }

    public String addrType = "noaddr";
    public String coorType = "bd09ll";
    public boolean disableLocCache;
    public boolean enableSimulateGps;
    public FirstLocType firstLocType;
    public boolean isEnableBeidouMode;
    public boolean isIgnoreKillProcess;
    public boolean isNeedAddress;
    public boolean isNeedLocationPoiList;
    public boolean isOnceLocation;
    public boolean openGps;
    public int scanSpan;
    public int timeOut = 20000;
    public int wifiCacheTimeOut;
    public LocationMode locationMode = LocationMode.Hight_Accuracy;

    /** What the adapter called, in order, so a test can assert the whole option block, not just its fields. */
    public final List<String> calls = new ArrayList<>();

    public void setLocationMode(LocationMode mode) {
        this.locationMode = mode;
        calls.add("setLocationMode(" + mode + ")");
    }

    public void setIsNeedAddress(boolean value) {
        this.isNeedAddress = value;
        this.addrType = value ? "all" : "noaddr";
        calls.add("setIsNeedAddress(" + value + ")");
    }

    public void setIsNeedLocationPoiList(boolean value) {
        this.isNeedLocationPoiList = value;
        calls.add("setIsNeedLocationPoiList(" + value + ")");
    }

    public void setOpenGnss(boolean value) {
        this.openGps = value;
        calls.add("setOpenGnss(" + value + ")");
    }

    public void setEnableSimulateGnss(boolean value) {
        this.enableSimulateGps = value;
        calls.add("setEnableSimulateGnss(" + value + ")");
    }

    public void setOnceLocation(boolean value) {
        this.isOnceLocation = value;
        calls.add("setOnceLocation(" + value + ")");
    }

    public void setFirstLocType(FirstLocType value) {
        this.firstLocType = value;
        calls.add("setFirstLocType(" + value + ")");
    }

    public void setWifiCacheTimeOut(int value) {
        this.wifiCacheTimeOut = value;
        calls.add("setWifiCacheTimeOut(" + value + ")");
    }
}
