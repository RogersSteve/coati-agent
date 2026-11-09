plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "bot.coati"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "bot.coati"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native OCR (ncnn) is built for 64-bit ARM devices and the x86_64 benchmark emulator;
        // 32-bit ABIs are rare at minSdk 30.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // On-device OCR: PaddleOCR PP-OCRv5 on ncnn's Vulkan backend (see src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
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
    }

    packaging {
        // The sandbox executes libproot.so from nativeLibraryDir, so it must exist as a file on disk.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "xz"
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Security
    implementation(libs.security.crypto)

    // Network
    implementation(libs.okhttp)

    // Linux sandbox rootfs decompression
    implementation(libs.xz)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    // Replaces Android's non-functional org.json JVM stubs during local tests.
    testImplementation(libs.json)
}
