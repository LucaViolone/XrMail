plugins {
    // Android app
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Backend server — declared here so Gradle resolves one shared version
    // across all modules and avoids "already on classpath" conflicts
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false

    // Firebase — processes google-services.json into generated resources
    alias(libs.plugins.google.services) apply false
}
