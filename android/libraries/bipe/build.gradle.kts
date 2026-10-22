// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Bipe logic for the Android app. src/main/kotlin/chat/bipe/shared/generated and
// src/main/res come from bipe-shared tools/gen.mjs --app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "chat.bipe.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// SharedVectorTest reads bipe-shared/test-vectors. CI checks bipe-shared out and sets BIPE_SHARED.
tasks.withType<Test>().configureEach {
    System.getenv("BIPE_SHARED")?.let { environment("BIPE_SHARED", it) }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    // BadgeVerifier: Ed25519 signatures of Bipe badges.
    implementation(libs.tink.android)
    implementation(libs.timber)
    // BipeColors (generated from bipe-shared design/tokens.json) exposes Compose types.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui.graphics)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
