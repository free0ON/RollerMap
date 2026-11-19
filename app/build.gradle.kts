plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.rollers.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.rollers.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // адрес сервера: ./gradlew :app:assembleDebug -Prollers.apiUrl=https://api.example.ru
        val apiUrl = (project.findProperty("rollers.apiUrl") as String?) ?: "http://10.0.2.2:8080"
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        buildConfigField("String", "OSRM_URL", "\"https://routing.openstreetmap.de/routed-foot\"")
        buildConfigField("String", "OVERPASS_URL", "\"https://overpass-api.de/api/interpreter\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.core.ktx)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.play.location)
    implementation(libs.osmdroid)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.json)
}
