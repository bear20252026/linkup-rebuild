package com.amap.api.location;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the AMap location client, shaped from the official AMap location SDK API — the real
 * contract this adapter binds, since the shipping app never carried AMap and there is no decompiled source
 * to transcribe.
 *
 * It is NOT the SDK: no positioning logic, lives under `src/test/java`, never ships. Its job is to let
 * `AmapLocationProvider`'s reflective bindings run offline — the production adapter only ever sees
 * `com.amap.api.location.*` through reflection, so a double with the same fully-qualified names exercises
 * the real code path.
 *
 * The privacy pair is static and counted, because the adapter's privacy gate is what keeps an unagreed
 * client from ever being constructed.
 */
public class AMapLocationClient {

    private static final List<String> log = new ArrayList<>();

    public static String apiKey;
    public static int privacyShowCount;
    public static int privacyAgreeCount;
    public static Boolean lastPrivacyAgreeValue;

    private final Context context;
    private AMapLocationClientOption option;
    private AMapLocationListener listener;

    public int setLocationListenerCount;
    public int setLocationOptionCount;
    public int startCount;
    public int stopCount;
    public int destroyCount;
    public boolean started;

    public AMapLocationClient(Context context) {
        this.context = context;
        record("ctor(" + (context == null ? "null" : "context") + ")");
    }

    public static void setApiKey(String key) {
        apiKey = key;
        record("setApiKey(" + key + ")");
    }

    public static void updatePrivacyShow(Context context, boolean privacyPolicyContains, boolean privacyComplianceContains) {
        privacyShowCount++;
        record("updatePrivacyShow(" + privacyPolicyContains + "," + privacyComplianceContains + ")");
    }

    public static void updatePrivacyAgree(Context context, boolean agree) {
        privacyAgreeCount++;
        lastPrivacyAgreeValue = agree;
        record("updatePrivacyAgree(" + agree + ")");
    }

    public void setLocationListener(AMapLocationListener listener) {
        this.listener = listener;
        this.setLocationListenerCount++;
        record("setLocationListener");
    }

    public void setLocationOption(AMapLocationClientOption option) {
        this.option = option;
        this.setLocationOptionCount++;
        record("setLocationOption");
    }

    public AMapLocationClientOption getLocationOption() {
        return option;
    }

    public void startLocation() {
        this.started = true;
        this.startCount++;
        record("startLocation");
    }

    public void stopLocation() {
        this.started = false;
        this.stopCount++;
        record("stopLocation");
    }

    public void onDestroy() {
        this.destroyCount++;
        record("onDestroy");
    }

    /** Test hook: hands a fix (or a failure carrying its own errorCode) to the registered listener. */
    public void deliver(AMapLocation location) {
        if (listener == null) {
            throw new IllegalStateException("no listener registered");
        }
        listener.onLocationChanged(location);
    }

    public boolean hasListener() {
        return listener != null;
    }

    public static List<String> log() {
        return log;
    }

    public static void record(String call) {
        log.add(call);
    }

    public static void resetState() {
        log.clear();
        apiKey = null;
        privacyShowCount = 0;
        privacyAgreeCount = 0;
        lastPrivacyAgreeValue = null;
    }

    public Context context() {
        return context;
    }
}
