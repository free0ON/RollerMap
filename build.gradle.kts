buildscript {
    // Android Gradle Plugin нужен только когда есть Android SDK (см. settings.gradle.kts)
    val hasAndroid = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
        File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir=") }
    repositories { google(); mavenCentral() }
    if (hasAndroid) dependencies { classpath("com.android.tools.build:gradle:8.7.3") }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktor) apply false
}
