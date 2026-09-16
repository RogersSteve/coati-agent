plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "bot.coati.fixture.manual"
    compileSdk = 35

    defaultConfig {
        applicationId = "bot.coati.fixture.manual"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "layoutVersion"
    productFlavors {
        create("v1") {
            dimension = "layoutVersion"
            versionCode = 1
            versionNameSuffix = "-v1"
        }
        create("v2") {
            dimension = "layoutVersion"
            versionCode = 2
            versionNameSuffix = "-v2"
        }
    }

    sourceSets {
        // Both installable variants deliberately share one deterministic implementation. Runtime
        // version branching changes only the navigation layout while preserving package/signature.
        getByName("v2").java.srcDir("src/v1/java")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)

    testImplementation(libs.junit)
}
