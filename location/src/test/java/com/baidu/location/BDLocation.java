package com.baidu.location;

import java.util.List;

/**
 * Test double for `BDLocation`, carrying the getters `manager/x0.java:284-304` actually read.
 *
 * `coorType` defaults to `bd09ll` because that is what the shipping option block asked for
 * (`dg/a.java:76-79`); a test that wants the gcj02 path sets it explicitly, which is exactly how this layer
 * avoids assuming a datum.
 */
public class BDLocation {

    public double latitude = 39.90469;
    public double longitude = 116.40717;
    public float radius = 25f;
    public float speed = 3.6f;
    public int locType = 61;
    public String coorType = "bd09ll";
    public String province = "北京市";
    public String city = "北京";
    public String district = "东城区";
    public String street = "长安街";
    public String streetNumber = "1号";
    public List<Poi> poiList;

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getRadius() {
        return radius;
    }

    public float getSpeed() {
        return speed;
    }

    public int getLocType() {
        return locType;
    }

    public String getCoorType() {
        return coorType;
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

    public String getStreetNumber() {
        return streetNumber;
    }

    public List<Poi> getPoiList() {
        return poiList;
    }
}
