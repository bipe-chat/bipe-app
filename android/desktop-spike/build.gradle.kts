// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Standalone JVM project: no Android plugin, SDK, application dependency or device required.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../../core/harness/kotlin")
}

dependencies {
    implementation(project(":desktop-runtime"))
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

application { mainClass = "chat.bipe.desktop.spike.MainKt" }

tasks.named<JavaExec>("run") {
    systemProperty("jna.library.path", providers.environmentVariable("BIPE_NATIVE_DIR").get())
    args(providers.environmentVariable("BIPE_VECTORS").get(),
         providers.environmentVariable("BIPE_SDK_REV").get())
}
