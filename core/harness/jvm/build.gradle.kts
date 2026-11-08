// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

// BIPE_CORE_JAR: run against a release jar (core/release/jvm) instead of generated/ and BIPE_CORE_LIB_DIR.
val coreJar: String? = providers.environmentVariable("BIPE_CORE_JAR").orNull

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDirs(listOfNotNull("src", "../kotlin", "generated".takeIf { coreJar == null }))
}

dependencies {
    if (coreJar != null) implementation(files(coreJar))
    implementation("net.java.dev.jna:jna:5.17.0")
    // The verifier of the Android app (tink-android in android/gradle/libs.versions.toml), for the Ed25519 cases.
    implementation("com.google.crypto.tink:tink:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass = "chat.bipe.core.harness.MainKt"
}

tasks.named<JavaExec>("run") {
    if (coreJar == null) systemProperty("jna.library.path", providers.environmentVariable("BIPE_CORE_LIB_DIR").get())
    args(providers.environmentVariable("BIPE_VECTORS").get(), providers.environmentVariable("BIPE_ED25519_CASES").get())
}
