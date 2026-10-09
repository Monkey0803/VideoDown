import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing is read from a git-ignored `keystore.properties` at the repo root, so no real
 * signing key is ever committed. See `keystore.properties.example`.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile") != null

/**
 * Target CPU architecture. `arm64-v8a` covers essentially every phone shipped since 2017, so it
 * is the default; pass `-Pabi=universal` to bundle every ABI (needed for x86_64 emulators).
 *
 * This uses `ndk.abiFilters` rather than ABI *splits* on purpose: with splits enabled, running
 * `testDebugUnitTest` together with `assembleDebug`/`assembleRelease` intermittently fails inside
 * AGP's `PackageAndroidArtifact$IncrementalSplitterRunnable`. `abiFilters` produces one
 * deterministic APK per build type with no splitter involved.
 */
val targetAbi: String = (findProperty("abi") as String?)?.lowercase() ?: "arm64"

val abiFiltersForBuild: List<String> = when (targetAbi) {
    "arm64", "arm64-v8a", "aarch64" -> listOf("arm64-v8a")
    "arm32", "armeabi-v7a", "v7a" -> listOf("armeabi-v7a")
    "x86_64", "x64" -> listOf("x86_64")
    "x86" -> listOf("x86")
    "universal", "all" -> listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    else -> throw GradleException(
        "Unsupported -Pabi=$targetAbi. Expected one of: arm64, armeabi-v7a, x86, x86_64, universal.",
    )
}

/** Short tag used in the output APK file name. */
val abiTag: String = if ("universal" == targetAbi || "all" == targetAbi) "universal" else abiFiltersForBuild.first()

/**
 * Default endpoint for the in-app update check. Baked into `BuildConfig` so an official build can
 * ship a working update source, while users can still override it in 「我的 → 检查更新 → 更新源」.
 * Pass `-PupdateManifestUrl=https://…/version.json` to set it.
 */
val defaultUpdateManifestUrl: String = (findProperty("updateManifestUrl") as String?)?.trim().orEmpty()

android {
    namespace = "com.videodown.douyin"
    compileSdk = 36

    // Pinned to the Build Tools that ship with this SDK installation; without it AGP asks for
    // its own default (35.0.0) and tries to download it.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.videodown.douyin"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField(
            "String",
            "DEFAULT_UPDATE_MANIFEST_URL",
            "\"${defaultUpdateManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        /**
         * Restricts packaged native libraries to [abiFiltersForBuild].
         *
         * The app has no native code of its own; the only `.so` comes from
         * `androidx.graphics.path`. Filtering keeps the artifact explicitly single-ABI so it
         * cannot be installed on an architecture it was not built for.
         */
        ndk {
            abiFilters.clear()
            abiFilters.addAll(abiFiltersForBuild)
        }
    }

    /**
     * A checked-in debug keystore keeps `assembleDebug` reproducible on any machine, including
     * CI and sandboxes where `~/.android` is not writable.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to unsigned when no release key is configured.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * Makes the artifact self-describing, e.g. `videodown-arm64-v8a-release.apk`, so an arm64 build
 * can never be confused with a universal one.
 */
base {
    archivesName.set("videodown-$abiTag")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
