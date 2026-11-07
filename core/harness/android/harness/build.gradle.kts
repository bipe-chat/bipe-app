// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// A host app for the instrumented test. It takes the AAR as a file, as a client would
// get it, and the Matrix SDK of ../../../../android/gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "chat.bipe.core.harness"
    compileSdk = 36
    defaultConfig {
        applicationId = "chat.bipe.core.harness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The emulator's ABI. The Matrix SDK library is large in each ABI.
        ndk { abiFilters += "x86_64" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["androidTest"].kotlin.srcDir("../../kotlin")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(files("../bipecore/build/outputs/aar/bipecore-release.aar"))
    implementation(libs.matrix.rust.sdk)
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(project(":bipe"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
