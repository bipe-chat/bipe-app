// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The phone run of bipe-chat/bipe#1277 (items 5 and 8): the release AAR on a real arm64-v8a phone. The app runs
// bipe-shared test-vectors/badges.json through bipe-core and shows the p95 of one verification against the budget.
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val release = JsonSlurper().parse(rootDir.resolve("../release.json")) as Map<*, *>
val coreVersion = release["version"] as String

android {
    namespace = "chat.bipe.core.bench"
    compileSdk = 36
    defaultConfig {
        applicationId = "chat.bipe.core.bench"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = coreVersion
    }
    // CI signs with the fixed tester key (tools/tester-keystore.sh), the same properties as android/app.
    signingConfigs {
        val storeFile = findProperty("bipeUploadStoreFile") as String?
        if (storeFile != null) {
            create("upload") {
                this.storeFile = file(storeFile)
                storePassword = findProperty("bipeUploadStorePassword") as String?
                keyAlias = findProperty("bipeUploadKeyAlias") as String?
                keyPassword = findProperty("bipeUploadKeyPassword") as String?
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The vector runner that the emulator and JVM harnesses use.
    sourceSets["main"].kotlin.srcDir("../../../harness/kotlin")
    // core.yml copies badges.json here.
    sourceSets["main"].assets.srcDir("vectors")
    packaging { jniLibs { keepDebugSymbols += "**/libbipe_core.so" } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("chat.bipe.core:bipe-core:$coreVersion@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
