plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.notes"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.notes"
        minSdk = 24
        targetSdk = 37
        versionCode = 25

        versionName = "5.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // V5.0.2: testers want a release-style APK that they can
            // sideload without an attached debug key. The Android
            // Gradle Plugin signs release builds with the debug
            // signing config when no signingConfig is specified, so
            // this is installable on any device. When the project
            // gets a real keystore this block should reference it.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // V4.4: enable BuildConfig so log calls can check BuildConfig.DEBUG
        // and skip verbose debug output in release builds.
        buildConfig = true
    }
    // V5.0.2: Robolectric needs the merged AndroidManifest and resources
    // on the unit-test classpath; without this the test runner fails to
    // initialize the Application context and every test throws
    // "Some resetters failed".
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Kotlinx Serialization (V3.1 — online auth + sync)
    implementation(libs.kotlinx.serialization.json)

    // Google Sign-In (V4.2 — "Continue with Google" button)
    implementation(libs.google.android.play.services.auth)

    // V4.4 — OkHttp replaces HttpURLConnection for proper connection
    // pooling, timeouts, retry, and HTTP/2 support.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // V5.0 — WorkManager for periodic background sync. The Sync button
    // remains the manual trigger; the periodic worker is a safety net
    // for users who never tap it.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}