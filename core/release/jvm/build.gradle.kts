// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Inputs, written by core.yml: generated/ (the Kotlin bindings) and natives/<JNA prefix>/ (linux-x86-64,
// win32-x86-64). JNA's Native.load finds the library under its platform prefix on the class path.
plugins {
    kotlin("jvm") version "2.2.21"
}

group = "chat.bipe.core"
version = providers.gradleProperty("bipeCoreVersion").getOrElse("0.0.0")

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("generated")
}

sourceSets["main"].resources.srcDir("natives")

dependencies {
    // The generated bindings call JNA and may import kotlinx.coroutines. The jar has no POM, so a consumer names both
    // (core/release/README.md).
    compileOnly("net.java.dev.jna:jna:5.17.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.jar {
    archiveFileName = "bipe-core-jvm-${project.version}.jar"
    manifest { attributes("Implementation-Title" to "bipe-core", "Implementation-Version" to project.version) }
}
