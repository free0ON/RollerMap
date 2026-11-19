pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "rollers"
include(":core", ":server")
// Android-модуль подключается только при наличии Android SDK
val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: File(rootDir, "local.properties").takeIf { it.exists() }?.readText()
        ?.lineSequence()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
if (sdk != null) include(":app")
