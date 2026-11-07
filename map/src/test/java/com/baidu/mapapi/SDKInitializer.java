package com.baidu.mapapi;

import android.content.Context;

/**
 * Test double for the Baidu map initializer, carrying the two statics the adapter binds. The privacy
 * agreement is counted so the adapter's gate is provable, not assumed.
 */
public class SDKInitializer {

    public static Boolean agreedPrivacy;
    public static int initializeCount;

    public static void setAgreePrivacy(boolean agree) {
        agreedPrivacy = agree;
    }

    public static void initialize(Context context) {
        initializeCount++;
    }

    public static void resetState() {
        agreedPrivacy = null;
        initializeCount = 0;
    }
}
