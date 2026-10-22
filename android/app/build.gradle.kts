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

    // pt-BR is the default language (res/values), en the second one (res/values-en).
    // Keep only these two in the APK, so dependency strings in other languages never show.
    // The per-app language setting reads res/xml/locales_config.xml.
    androidResources {
        localeFilters += listOf("pt-rBR", "en")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Link a computer (spec link-computer-scan): the camera and the QR decoder.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
