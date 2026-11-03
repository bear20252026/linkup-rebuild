plugins {
    id("com.android.library")
}

android {
    namespace = "com.youloft.watcher.res166"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    // ViewBinding stays off here: the bank holds 936 layouts whose custom views (map, ad and IM
    // SDKs) are not on this module's classpath, so generated bindings cannot compile. Each rebuilt
    // screen copies the layouts it owns into :app, where the view classes do resolve.
    buildFeatures {
        viewBinding = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
