plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.avijit.orbit"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.avijit.orbit"
        minSdk = 33
        targetSdk = 35
        versionCode = 20
        versionName = "0.12.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Release signing: the key lives outside the repo (~/.gradle/gradle.properties points to it).
    // Without it (e.g. contributors' machines) release builds fall back to the debug key.
    val releaseStore = (findProperty("ORBIT_RELEASE_STORE_FILE") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = findProperty("ORBIT_RELEASE_STORE_PASSWORD") as String
            keyAlias = findProperty("ORBIT_RELEASE_KEY_ALIAS") as String
            keyPassword = findProperty("ORBIT_RELEASE_KEY_PASSWORD") as String
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same key as release when available, so debug and release builds update each other.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            // No shrinking: the helper, AIDL and libadb rely on reflection and class names.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Built-in helper start: Wireless-debugging pairing + adb client (Apache-2.0 option; SPAKE2 dep is LGPL-3.0).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
}
