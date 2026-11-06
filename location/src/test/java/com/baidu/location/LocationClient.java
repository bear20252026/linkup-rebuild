package com.baidu.location;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the Baidu location client, shaped from `_raw/out_jadx_156/sources/com/baidu/location/
 * LocationClient.java` and recorded in `recovered/baidu_location_api_156.txt`.
 *
 * It is NOT the SDK: it has no positioning logic, lives under `src/test/java`, and is never compiled into
 * anything that ships. Its job is to let `BaiduLocationProvider`'s reflective bindings run offline — the
 * production adapter only ever sees `com.baidu.location.*` through reflection, so a double with the same
 * fully-qualified names exercises the real code path.
 *
 * Both `registerLocationListener` overloads exist on purpose (`LocationClient.java:1203,1213`), because
 * which one the adapter picks is the whole reason the listener is a dynamic proxy. There is deliberately no
 * `unregisterLocationListener`: neither APK declares one (`recovered/baidu_location_api_156.txt:15-16`), so
 * a double that had one would let the binding pass a test the real SDK could never answer.
 */
public class LocationClient {

    private static final List<String> log = new ArrayList<>();

    public static Boolean agreedPrivacy;
    public static String apiKey;

    private final Context context;
    private LocationClientOption option;
    private BDLocationListener interfaceListener;
    private BDAbstractLocationListener abstractListener;

    public int setLocOptionCount;
    public int registerCount;
    public int startCount;
    public int stopCount;
    public int requestLocationCount;
    public int requestLocationResult = 0;
    public boolean started;

    public LocationClient(Context context) {
        this.context = context;
        record("ctor(" + (context == null ? "null" : "context") + ")");
    }

    public LocationClient(Context context, LocationClientOption option) {
        this.context = context;
        this.option = option;
        record("ctor(context, option)");
    }

    public static void setAgreePrivacy(boolean value) {
        agreedPrivacy = value;
        record("setAgreePrivacy(" + value + ")");
    }

    public static void setKey(String key) {
        apiKey = key;
        record("setKey(" + key + ")");
    }

    public LocationClientOption getLocOption() {
        return option;
    }

    public void registerLocationListener(BDAbstractLocationListener listener) {
        this.abstractListener = listener;
        this.registerCount++;
        record("registerLocationListener(abstract)");
    }

    public void registerLocationListener(BDLocationListener listener) {
        this.interfaceListener = listener;
        this.registerCount++;
        record("registerLocationListener(interface)");
    }

    public void setLocOption(LocationClientOption option) {
        this.option = option;
        this.setLocOptionCount++;
        record("setLocOption");
    }

    public void start() {
        this.started = true;
        this.startCount++;
        record("start");
    }

    public void stop() {
        this.started = false;
        this.stopCount++;
        record("stop");
    }

    public boolean isStarted() {
        return started;
    }

    public int requestLocation() {
        this.requestLocationCount++;
        record("requestLocation");
        return requestLocationResult;
    }

    /** Test hook: hands a fix to whichever listener the adapter registered. */
    public void deliver(BDLocation location) {
        if (interfaceListener != null) {
            interfaceListener.onReceiveLocation(location);
        } else if (abstractListener != null) {
            abstractListener.onReceiveLocation(location);
        } else {
            throw new IllegalStateException("no listener registered");
        }
    }

    /** True when the adapter bound the interface overload, which is what the proxy can satisfy. */
    public boolean hasInterfaceListener() {
        return interfaceListener != null;
    }

    public static List<String> log() {
        return log;
    }

    public static void record(String call) {
        log.add(call);
    }

    public static void resetState() {
        log.clear();
        agreedPrivacy = null;
        apiKey = null;
    }

    public Context context() {
        return context;
    }
}
