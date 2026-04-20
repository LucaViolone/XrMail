import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.xremail.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xremail.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        val localProps = rootProject.file("local.properties")
        val geminiKey = if (localProps.exists()) {
            val props = Properties()
            localProps.inputStream().use { props.load(it) }
            props.getProperty("GEMINI_API_KEY", "")
        } else ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

        // Ktor backend URL.
        //
        // `localhost` (not 10.0.2.2) works on BOTH the emulator and real XR
        // hardware as long as `adb reverse tcp:8081 tcp:8081` is wired up —
        // start.sh does that automatically. 10.0.2.2 is the emulator-only
        // host-loopback alias and silently fails (~30s connect timeout) on
        // a physical Galaxy XR, which was what made "Sign in with Google"
        // hang on the spinner.
        //
        // Port 8081 matches ktor.deployment.port in backend/application.conf
        // (chosen to avoid clashing with :8080 that dev tools often grab).
        buildConfigField("String", "BACKEND_URL", "\"http://localhost:8081/\"")
        buildConfigField("Boolean", "USE_REAL_BACKEND", "false")
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "USE_REAL_BACKEND", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.work.runtime)

    // Networking — Retrofit + OkHttp for backend communication
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Coroutines (Android dispatcher)
    implementation(libs.kotlinx.coroutines.android)

    // Encrypted token storage (JWT persistence)
    implementation(libs.security.crypto)

    // Chrome Custom Tabs — opens OAuth flow without leaving the app
    implementation(libs.androidx.browser)

    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.material3)
    implementation(libs.androidx.xr.arcore)

    // Firebase AI Logic — Gemini Live bidirectional audio session
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)

    // firebase-ai exposes JsonObject in its public API
    implementation(libs.kotlinx.serialization.json)
}
