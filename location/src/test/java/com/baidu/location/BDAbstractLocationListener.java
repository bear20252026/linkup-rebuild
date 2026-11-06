package com.baidu.location;

/**
 * `LocationClient.java:1203` also accepts this abstract class, which a proxy cannot implement. Keeping it
 * in the double is what makes the overload-selection in `BaiduSdk.registerListener` a real test rather than
 * an assumption: if the adapter ever resolves to this method, the argument check fails and the listener is
 * reported as never registered.
 */
public abstract class BDAbstractLocationListener implements BDLocationListener {

    @Override
    public abstract void onReceiveLocation(BDLocation location);
}
