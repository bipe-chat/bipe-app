// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("generated")
}
dependencies {
    api(libs.jna)
    implementation(libs.jna.platform)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Native tests run only on a Windows CI host with the pinned SDK DLL already staged.
tasks.test {
    providers.environmentVariable("BIPE_NATIVE_DIR").orNull?.let { systemProperty("jna.library.path", it) }
}
