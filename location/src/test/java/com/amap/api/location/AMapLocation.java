package com.amap.api.location;

/**
 * Test double for `AMapLocation`, carrying the getters `AmapSdk.readFix` actually reads. Field names are
 * the official SDK's; `coordType` defaults to `GCJ02` because that is what the real fix always answers, and
 * a test that wants another datum sets it explicitly.
 *
 * `speed` is in m/s and `getSpeed()` hands it over unchanged — the whole point of the AMap speed test, since
 * unlike Baidu there is no unit conversion anywhere in this vendor's path.
 */
public class AMapLocation {

    public double latitude = 39.90469;
    public double longitude = 116.40717;
    public float accuracy = 25f;
    public float speed = 1.5f;
    public float bearing = 0f;
    public int errorCode = 0;
    public String errorInfo = "";
    public int locationType = 1;
    public String coordType = "GCJ02";
    public String province = "北京市";
    public String city = "北京";
    public String district = "东城区";
    public String street = "长安街";
    public String streetNum = "1号";
    public String poiName = "天安门";

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public float getSpeed() {
        return speed;
    }

    public float getBearing() {
        return bearing;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorInfo() {
        return errorInfo;
    }

    public int getLocationType() {
        return locationType;
    }

    public String getCoordType() {
        return coordType;
    }

    public String getProvince() {
        return province;
    }

    public String getCity() {
        return city;
    }

    public String getDistrict() {
        return district;
    }

    public String getStreet() {
        return street;
    }

    public String getStreetNum() {
        return streetNum;
    }

    public String getPoiName() {
        return poiName;
    }
}
