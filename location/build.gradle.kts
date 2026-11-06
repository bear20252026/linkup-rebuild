plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.youloft.linkup.location"
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
 * The location layer deliberately depends on nothing but the platform.
 *
 * A vendor SDK must never be required to compile the interface, because the whole point is that the app
 * builds when the Baidu AAR is missing — which is exactly the state this repository is in. The Baidu
 * adapter reaches its SDK by reflection and the framework adapter by `android.location`, and the doubles
 * that stand in for the SDK are test-only, so no vendor class ever enters the runtime classpath from here.
 */
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(kotlin("test"))
}
