plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.mc.fastkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    // Enabled so the dialog base can compile against androidx.viewbinding.ViewBinding; this module
    // holds no layouts, so nothing is generated here.
    buildFeatures {
        viewBinding = true
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

dependencies {
    api(project(":res166"))
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.constraintlayout:constraintlayout:2.1.4")
    api("androidx.core:core-ktx:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(kotlin("test"))
}
