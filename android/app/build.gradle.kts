// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// IDs: ADR 0147. Tester builds use their own application ID (chat.bipe.app.next), so they
// sit next to the fork on one phone. At the switch the app takes the fork's ID (ADR 0061).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chat.bipe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "chat.bipe.app.next"
        minSdk = 26
        targetSdk = 36
        // CI passes the tester build number (bipe-shared product/version.json build plus the run number).
        versionCode = (findProperty("bipeBuildNumber") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Tester APK for side-loading (bipe-chat/bipe#575): debug signing is enough.
            // Store signing comes at the switch.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":libraries:bipe"))
    implementation(project(":libraries:phosphor"))
    implementation(libs.matrix.rust.sdk)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR codes of profile links (spec profile-link), drawn on the device.
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
