// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDirs("src", "../kotlin", "generated")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass = "chat.bipe.core.harness.MainKt"
}

tasks.named<JavaExec>("run") {
    systemProperty("jna.library.path", providers.environmentVariable("BIPE_CORE_LIB_DIR").get())
    args(providers.environmentVariable("BIPE_VECTORS").get())
}
