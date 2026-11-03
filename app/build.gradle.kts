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
    implementation(project(":net"))
    implementation(project(":res166"))
    implementation(project(":fastkit"))

    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta02")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.airbnb.android:lottie:6.4.0")
    implementation("io.github.cymchad:BaseRecyclerViewAdapterHelper4:4.1.7")


    // --- recovered third-party UI dependencies (probe batch) ---
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("io.supercharge:shimmerlayout:2.1.0")
    implementation("com.github.yalantis:ucrop:2.2.11")
    implementation("io.github.scwang90:refresh-layout-kernel:2.0.5")
    implementation("io.github.scwang90:refresh-header-classics:2.0.5")
    implementation("io.github.youth5201314:banner:2.2.3")
    // unresolved from public repos during this pass; company Nexus or vendor account needed:
    //   com.dinuscxj:circleprogressbar (4 layouts) · com.github.lihangoffice:ShadowLayout (2)
    //   com.github.warkiz.*:indicatorseekbar (2)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.kyleduo.switchbutton:library:2.1.0")
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1")
    implementation("io.github.lucksiege:pictureselector:v3.11.2")
    implementation("com.github.gzu-liyujiang:AndroidPicker:4.1.13")
    implementation("com.github.daimajia:AndroidSwipeLayout:v1.1.8") {
        // this 2014 artifact still depends on com.android.support, which collides with AndroidX
        exclude(group = "com.android.support")
    }
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(kotlin("test"))
}

/**
 * Downloads everything on the debug classpath so tools/strip_lib_res.py can read the .aar files.
 * `gradle :app:dependencies` only resolves metadata, which silently leaves new libraries unstripped.
 */
tasks.register("fetchDebugArtifacts") {
    group = "recovery"
    description = "Resolve and download debugRuntimeClasspath artifacts into the Gradle cache"
    doLast {
        // The Android runtime classpath is attribute-typed (resolving it directly is ambiguous) and
        // carries its declarations through extended configurations, so copy allDependencies across.
        val external = configurations.getByName("debugRuntimeClasspath").allDependencies
            .filterIsInstance<ExternalModuleDependency>()
            .toTypedArray()
        val mirror = configurations.detachedConfiguration(*external).apply { isTransitive = true }
        println("resolved " + mirror.resolve().size + " artifact files")
    }
}
