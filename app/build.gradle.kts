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
        versionCode = 1
        versionName = "1.0-mvp"

        // Only ship the ABIs ARCore actually supports. This keeps the APK
        // small, which matters on the mid-range devices we target.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    testImplementation("junit:junit:4.13.2")
}
