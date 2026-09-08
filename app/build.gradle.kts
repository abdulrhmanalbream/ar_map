plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sarab.vision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sarab.vision"
        // ARCore requires API 24+. 24 covers the overwhelming majority of
        // mid-range devices in the field.
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "3.0-companion-preview"

        // NOTE: no ndk.abiFilters here. The ABI restriction lives in the
        // `splits` block below, and setting both is a configuration error.
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    // Source attribution travels with the APK, including offline installs.
    sourceSets["main"].assets.srcDir(rootProject.file("third_party"))

    // Split by ABI.
    //
    // MapLibre's native library is ~12.5MB PER ABI, so shipping both in one
    // APK cost 36MB when only one is ever used. Splitting means a device
    // downloads roughly 23MB instead, and both architectures stay supported.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // No universal APK: it would defeat the point by bundling both.
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // ARCore. We render with raw OpenGL ES rather than Sceneform because
    // Sceneform is archived and its Filament dependency adds ~10MB plus
    // meaningful GPU cost. See docs/ADR-001.
    implementation("com.google.ar:core:1.45.0")

    // MapLibre: the same engine the reference web map uses, so the map can
    // reach that quality bar instead of the hand-drawn Canvas it replaces.
    // Crucially it ships an OfflineManager that downloads a region for use
    // with no network -- which the web map has no equivalent of.
    // Costs roughly 6MB of APK; worth it for a real map.
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // On-device text recognition, used to read the name off a building's
    // entrance plaque. The colleges here are visually identical, so the sign
    // is the only thing that separates them.
    //
    // The BUNDLED variant is deliberate: the play-services variant downloads
    // its model on first use, and an app whose whole promise is "works with no
    // connection" cannot have a feature that silently needs one. Costs roughly
    // 4MB. Latin script only -- ML Kit has no Arabic model -- but every plaque
    // on this campus carries an English line as well.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
