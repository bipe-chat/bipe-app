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

// The Firebase project's client values (not secrets: they ship in every APK). BuildConfig field to Gradle property.
val firebaseValues = mapOf(
    "FIREBASE_APP_ID" to "bipeFirebaseAppId",
    "FIREBASE_PROJECT_ID" to "bipeFirebaseProjectId",
    "FIREBASE_API_KEY" to "bipeFirebaseApiKey",
    "FIREBASE_SENDER_ID" to "bipeFirebaseSenderId",
)

android {
    namespace = "chat.bipe.app"
    compileSdk = 36

    defaultConfig {
        // ID set (bipe-chat/bipe#602, docs/client-switch-plan.md step 2): tester builds use chat.bipe.app.next;
        // -PbipeIdSet=store builds the fork's ID (ADR 0061). CI builds the store set unsigned and never uploads it
        // before the switch.
        applicationId = if (findProperty("bipeIdSet") == "store") "chat.bipe.app" else "chat.bipe.app.next"
        minSdk = 26
        targetSdk = 36
        // Every build passes the version from `node tools/version.mjs --gradle` (ADR 0088): the version name of
        // version.json (ADR 0163), its build plus the commit count, and the commit. Else placeholders.
        versionCode = (findProperty("bipeBuildNumber") as String?)?.toInt() ?: 1
        versionName = (findProperty("bipeVersionName") as String?) ?: "0.1.0"
        buildConfigField("String", "BIPE_COMMIT", "\"${(findProperty("bipeCommit") as String?).orEmpty()}\"")
    }

    // ADR 0150: the play flavour may use Google Block Store; foss has no Google Play services (the build without
    // Google that ADR 0150 keeps possible). Tester and store builds are play.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
            // FCM (ADR 0150): the Firebase project's client values, from CI repository variables. Empty: no pushes.
            for ((field, property) in firebaseValues) {
                buildConfigField("String", field, "\"${(findProperty(property) as String?).orEmpty()}\"")
            }
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    // Play upload key (bipe-chat/bipe#604): store-release.yml passes the keystore path with -PbipeUploadStoreFile and
    // the passwords and alias as ORG_GRADLE_PROJECT_* variables from secrets, so they never show on a command line.
    val uploadStoreFile = findProperty("bipeUploadStoreFile") as String?
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = findProperty("bipeUploadStorePassword") as String?
                keyAlias = findProperty("bipeUploadKeyAlias") as String?
                keyPassword = findProperty("bipeUploadKeyPassword") as String?
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Tester APK for side-loading (bipe-chat/bipe#575): debug signing is enough. The store release signs with
            // the Play upload key when it is given; Play then signs again with the app signing key.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // The SDK's TLS check calls org.rustls.platformverifier.CertificateVerifier through JNI (bipe-chat/bipe#626).
    implementation(libs.rustls.platform.verifier)
    implementation(libs.kotlinx.coroutines.android)
    // Structure report of the earlier app's data (bipe-chat/bipe#603, account/LegacyData.kt).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Video resize before upload (spec upload-preview, quality standard).
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.compose.material.icons.extended)
    // Link a computer (spec link-computer-scan): the camera and the QR decoder.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // Calls (spec call, ADR 0186): LiveKit media with per-participant E2EE keys.
    implementation(libs.livekit.android)
    implementation(libs.webrtc.android)
    // A locked phone rings (bipe-chat/bipe#760): Bluetooth, headset and car buttons, audio focus.
    implementation(libs.androidx.core.telecom)
    // Recovery token sync (ADR 0059, ADR 0150): src/play/.../BlockStoreRecoveryTokenSync.kt.
    "playImplementation"(libs.play.services.auth.blockstore)
    // Push transport (ADR 0150): src/play/.../PushTransports.kt.
    "playImplementation"(libs.firebase.messaging)
    // Deferred invite (ADR 0154): src/play/.../InstallReferrers.kt.
    "playImplementation"(libs.installreferrer)
}
