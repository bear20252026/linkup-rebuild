plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.youloft.watcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.youloft.watcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 166
        versionName = "1.6.6"
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":net"))
    implementation(project(":res166"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
