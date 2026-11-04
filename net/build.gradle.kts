plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Stamps the install-channel section into an APK the same way the recovered app reads it.
 * Run: gradle :net:tagChannel --args=<apk> <channel>
 */
tasks.register<JavaExec>("tagChannel") {
    group = "recovery"
    description = "Writes the 0x71777777 channel section into an already-signed APK (extra pairs sit outside the v2/v3 digest)"
    mainClass.set("com.youloft.linkup.net.ChannelTagger")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("liveVerify") {
    group = "verification"
    description = "Signs a real request with the recovered algorithm and calls the live backend"
    mainClass.set("com.youloft.linkup.net.LiveVerifyKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
