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
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/portable-kotlin"))
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/windows-oauth"))
}
val portableVectorDiff by tasks.registering(Sync::class) {
    from("../app/src/main/kotlin/chat/bipe/app/common/VectorDiff.kt")
    from("../libraries/bipe/src/main/kotlin/chat/bipe/shared/BipeFavouritesOrder.kt")
    from("../libraries/bipe/src/main/kotlin/chat/bipe/shared/generated/BipeRegistries.kt")
    from("../libraries/bipe/src/main/kotlin/chat/bipe/shared/generated/BipeReactions.kt")
    into(layout.buildDirectory.dir("generated/portable-kotlin"))
}
tasks.named("compileKotlin") { dependsOn(portableVectorDiff) }
val windowsOAuthMetadata by tasks.registering(Exec::class) {
    workingDir(projectDir.resolve("../.."))
    commandLine("node", "tools/windows-oauth.mjs", "--kotlin", layout.buildDirectory.dir("generated/windows-oauth").get().asFile.absolutePath)
    inputs.file(projectDir.resolve("../../windows/oauth-client.json"))
    inputs.file(projectDir.resolve("../../tools/windows-oauth.mjs"))
    inputs.file(projectDir.resolve("../../macos/BipeMac/MacAccount.swift"))
    outputs.file(layout.buildDirectory.file("generated/windows-oauth/WindowsOAuthMetadata.kt"))
}
tasks.named("compileKotlin") { dependsOn(windowsOAuthMetadata) }
dependencies {
    api(libs.jna)
    implementation(libs.jna.platform)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}

// Native tests run only on a Windows CI host with the pinned SDK DLL already staged.
tasks.test {
    testLogging.showStandardStreams = true
    providers.environmentVariable("BIPE_NATIVE_DIR").orNull?.let { systemProperty("jna.library.path", it) }
    environment("BIPE_SHARED", providers.environmentVariable("BIPE_SHARED").getOrElse(projectDir.resolve("../../../bipe-shared").absolutePath))
}
