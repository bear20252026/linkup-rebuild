package com.amap.api.location;

/**
 * Test double for the AMap listener interface — the official SDK's shape: one method, and failures arrive
 * through the same callback carrying their own `getErrorCode()`.
 */
public interface AMapLocationListener {

    void onLocationChanged(AMapLocation aMapLocation);
}
