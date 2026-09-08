plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sarab.vision"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sarab.vision"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "3.0.1-watch-preview"
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The wire format is shared with the phone; no phone camera/native libraries.
    sourceSets["main"].java.srcDir("../app/src/main/java/com/sarab/vision/wear/shared")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
