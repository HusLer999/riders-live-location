plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.riderslive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.riderslive"
        minSdk = 26   // BLE + FLP requirements; see docs/architecture.md for why not lower
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Local encrypted storage (Keystore-backed) — see security/KeyStoreManager.kt
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Encrypted SQLite (Room + SQLCipher) — see data/RideDatabase.kt
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Offline map rendering (vector tiles from a locally downloaded region)
    implementation("org.maplibre.gl:android-sdk:11.0.0")

    // Background work scheduling (adaptive interval GPS batching)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Kotlin coroutines for the BLE mesh event loop
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // X25519 ECDH is only available via the platform "XDH" provider on
    // API 33+ (Conscrypt). BouncyCastle gives us the same primitive back
    // to minSdk 26, so the handshake works on all supported devices.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
