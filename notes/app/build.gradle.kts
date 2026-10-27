import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// C5 — load release keystore credentials from app/keystore.properties (gitignored).
// The file MUST exist for release builds; debug builds are unaffected.
//
// Values are captured as plain Strings so the hooks below don't drag a
// Gradle `Project` (or any other script-proxy) reference into the
// configuration cache.
val keystorePropertiesFile: File = File(rootDir, "app/keystore.properties")
val keystorePropertiesAbsolutePath: String = keystorePropertiesFile.absolutePath
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val releaseSigningTaskNames: Set<String> = setOf(
    "validateSigningRelease",
    "packageRelease",
    "bundleRelease",
    "assembleRelease"
)

// C5 — explicit fail-fast for release packaging when the keystore is
// missing. The hook lives on the Gradle task graph (outside the android
// block) and only inspects plain Strings / Files, which play nicely with
// the configuration cache. `whenReady` fires after the graph is computed
// but before any task runs, so throwing here aborts the release build
// with the right error before AGP's `validateSigningRelease` reports its
// own (less informative) keystore error.
@Suppress("UnstableApiUsage")
gradle.taskGraph.whenReady {
    val needsRelease = allTasks.any { it.name in releaseSigningTaskNames }
    if (!needsRelease) return@whenReady
    val keyFile = File(keystorePropertiesAbsolutePath)
    if (!keyFile.exists()) {
        throw GradleException(
            "keystore.properties not found at $keystorePropertiesAbsolutePath " +
                "— required for release builds."
        )
    }
}

android {
    namespace = "com.example.notes"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.raximjonov.smartnotes"
        minSdk = 24
        targetSdk = 37
        versionCode = 2

        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // C5 — release signing config is wired up only when keystore.properties
    // is present. The release build type refuses to compile without it so
    // nobody ships a debug-signed APK by accident.
    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            val storeFileName = keystoreProperties["storeFile"] as String?
            storeFile = storeFileName?.let { rootProject.file("app/$it") }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    buildTypes {
        release {
            // C5 — wire up the release signing config (defined above) and
            // turn on R8 / resource shrinking. The signingConfig may be
            // populated with null fields when `keystore.properties` is
            // missing; the doFirst block below fails release packaging
            // with a clear error in that case so debug builds remain
            // unaffected.
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // C5 — explicit fail-fast for release packaging is registered at
    // the Gradle task-graph level (top of this file) so the doFirst
    // closure doesn't need to capture a `Project` reference — which
    // would otherwise trip the configuration cache.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
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

// B12 — emit Room's exported schema JSONs into app/schemas so a
// `MigrationTestHelper` (test-only) can pin the v9/v10/v11 schema
// shape and assert that MIGRATION_9_10 / MIGRATION_10_11 don't
// accidentally drop a column. The folder is gitignored by AGP's
// default `*.json` in app/build, so we add it explicitly to the
// repo via a sibling `.gitignore` allow.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Theme — `ui-text-google-fonts` enables `GoogleFont` + the Play Services
    // font provider for the Inter typography system. Without this dependency
    // Compose's `GoogleFont` constructor and `Font(googleFont, …)` overloads
    // don't resolve.
    implementation(libs.androidx.compose.ui.text.google.fonts)
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

    // C1 — EncryptedSharedPreferences for token storage on disk.
    implementation(libs.androidx.security.crypto)

    // C3 — Credential Manager + Google Identity Services. The
    // `credentials-play-services-auth` artefact pulls in the Play
    // Services auth shim so the bottom-sheet credential picker works
    // on devices that don't have the system Credential Manager yet.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid.lib)

    // Lottie Compose for modern animated illustrations and icons
    implementation(libs.lottie.compose)

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