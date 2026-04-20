plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.xremail.backend"
version = "0.1.0"

// Pin to JDK 17 — Gradle's test reporting has a known bug with JDK 24
// ("Type T not present"). This forces compilation and test execution to
// use JDK 17 regardless of the system JDK.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.xremail.backend.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor HTTP client (for Gmail API calls, Whisper, Gemini)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Gmail API (google-api-client already bundles oauth2 support)
    implementation(libs.google.api.client)
    implementation(libs.google.api.services.gmail)

    // JWT
    implementation(libs.java.jwt)

    // Database (Exposed ORM + H2 for token/session storage)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.h2.database)

    // JavaMail — needed by GmailService to build MIME messages for sending
    implementation(libs.java.mail)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}
