plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.youloft.linkup.map"
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
 * The map layer depends on the platform and on `:location` — for the datum conversions only.
 *
 * A vendor map SDK must never be required to compile the interface, because the whole point is that the
 * app builds when no map AAR exists — which is exactly the state this repository is in. The vendor
 * adapters reach their SDKs by reflection and the doubles that stand in for them are test-only, so no
 * vendor class ever enters the runtime classpath from here.
 *
 * `:location` is a deliberate dependency, not laziness: the bean stores WGS84 and each map vendor draws in
 * its own datum (Baidu BD09, AMap GCJ02). Those conversions already exist, tested line-by-line against the
 * recovered golden file — a second copy here would be two sources of truth for the one number that moves
 * points half a kilometre when wrong.
 */
dependencies {
    implementation(project(":location"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(kotlin("test"))
}
