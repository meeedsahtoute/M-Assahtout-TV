import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.streamtube.tv"
    compileSdk = 35
    base.archivesName.set("M-Assahtout-TV")

    defaultConfig {
        applicationId = "com.streamtube.tv"
        // minSdk 26 = Android 8.0 Oreo.
        // All shipping Google TV / Android TV devices (Chromecast 4K HD, Streamer,
        // NVIDIA Shield, Xiaomi TV Box S 2nd Gen, TCL/Hisense Google TV) run >= API 28.
        // We set 26 to cover any edge-case Android 8 TV boxes while still using
        // modern APIs (JobScheduler, autofill, picture-in-picture).
        minSdk = 26
        targetSdk = 35
        versionCode = 75
        versionName = "0.7.5-alpha"

        ndk {
            // arm64-v8a  → Chromecast with Google TV 4K, Google TV Streamer 4K,
            //              NVIDIA Shield, most 2022+ TV boxes
            // armeabi-v7a → Chromecast with Google TV HD, budget TV boxes running
            //               32-bit ART even on 64-bit SoCs (Amlogic S905X3, etc.)
            // Without BOTH abis the APK fails with INSTALL_FAILED_NO_MATCHING_ABIS
            // on 32-bit-mode Google TV devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH") ?: "${project.rootDir}/release.keystore"
            val ksFile = File(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "streamtube123"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "streamtube"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "streamtube123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // isShrinkResources requires isMinifyEnabled AND a valid R8 config.
            // Enabled here: ProGuard rules keep all JNI classes, so resource
            // shrinking is safe and reduces final APK size by ~15-20%.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH") ?: "${project.rootDir}/release.keystore"
            if (File(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/*.proto"
        }
        // Merge native .so files from both ABI directories cleanly
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Jetpack Compose for TV
    implementation("androidx.tv:tv-material:1.0.0")

    // Media3 / ExoPlayer — MergingMediaSource for separate video+audio adaptive streams
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Image loading (thumbnails, DeArrow custom thumbs)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON serialization (models DTO ↔ Kotlin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR code generation for Phone-to-TV Companion Server
    implementation("com.google.zxing:core:3.5.3")
}
