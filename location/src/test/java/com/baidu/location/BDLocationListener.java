package com.baidu.location;

/**
 * `com/baidu/location/BDLocationListener.java:4-5`. The adapter binds this interface through a dynamic
 * proxy, which is the reason the reflection approach works at all.
 */
public interface BDLocationListener {

    void onReceiveLocation(BDLocation location);
}
