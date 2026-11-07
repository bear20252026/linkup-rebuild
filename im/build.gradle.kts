plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.youloft.linkup.im"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * The IM module owns the one vendor dependency in the rebuild's client: the OpenIM Android SDK, which is
 * a *decision* (用户拍板), not a stopgap — the rebuilt gateway fronts OpenIMServer, and this SDK is its
 * official client. Everything above [ImClient] stays vendor-free; SDK types are allowed in this module
 * and nowhere else in `:app`'s line of sight.
 *
 * The SDK ships native libraries, so JVM tests cannot run it: the suite here drives the interface
 * against fakes and pins the mapping logic, and the real transport is the on-device verification item.
 */
dependencies {
    api("io.openim:android-sdk:3.8.3.1")
    api("io.openim:core-sdk:3.8.3-patch15")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(kotlin("test"))
}
