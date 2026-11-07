// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The AAR: the generated Kotlin and the libraries of core.yml (src/main/jniLibs).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "chat.bipe.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // core.yml stripped the libraries. AGP must not strip them again with another tool.
    packaging { jniLibs { keepDebugSymbols += "**/libbipe_core.so" } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(libs.kotlinx.coroutines.core)
}
