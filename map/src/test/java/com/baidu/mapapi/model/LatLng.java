package com.baidu.mapapi.model;

/** Test double for Baidu's LatLng container — fields exactly as the adapter reads them back in tests. */
public class LatLng {

    public final double latitude;
    public final double longitude;

    public LatLng(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LatLng)) return false;
        LatLng other = (LatLng) o;
        return Double.compare(latitude, other.latitude) == 0 && Double.compare(longitude, other.longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Double.valueOf(latitude).hashCode() * 31 + Double.valueOf(longitude).hashCode();
    }
}
