// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":desktop-runtime"))
    implementation(libs.compose.desktop.windows)
    implementation(libs.compose.desktop.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val repository = layout.projectDirectory.dir("../..")
val desktopResourceDirectory = layout.buildDirectory.dir("generated/bipe-resources")
val sharedKotlin = layout.buildDirectory.dir("generated/bipe-kotlin")
val desktopResources by tasks.registering(Exec::class) {
    workingDir(repository)
    commandLine("node", "tools/desktop-resources.mjs", desktopResourceDirectory.get().asFile.absolutePath)
    inputs.dir(repository.dir("../bipe-shared/strings"))
    inputs.dir(repository.dir("../bipe-shared/design"))
    inputs.file(repository.file("../bipe-shared/registries/scam-patterns.json"))
    inputs.file(repository.file("../bipe-shared/product/screens/scam-warning.json"))
    inputs.file(repository.file("../bipe-shared/registries/settings.json"))
    inputs.file(repository.file("../bipe-shared/registries/synced-settings.json"))
    inputs.file(repository.file("../bipe-shared/product/screens/settings-group.json"))
    inputs.file(repository.file("tools/desktop-resources.mjs"))
    inputs.file(repository.file("tools/windows-oauth.mjs"))
    inputs.file(repository.file("windows/oauth-client.json"))
    // The commit/build odometer must be read again, even if the shared catalogues have not changed.
    outputs.upToDateWhen { false }
}
val desktopTypography by tasks.registering(Sync::class) {
    from("../app/src/main/kotlin/chat/bipe/app/BipeType.kt")
    into(sharedKotlin)
}
kotlin.sourceSets["main"].kotlin.srcDir(sharedKotlin)
sourceSets["main"].resources.srcDir(desktopResourceDirectory)
tasks.named("compileKotlin") { dependsOn(desktopTypography) }
tasks.named("processResources") { dependsOn(desktopResources) }

val verifyWindowsOAuth by tasks.registering(Exec::class) {
    workingDir(repository)
    commandLine("node", "tools/windows-oauth.mjs", "--release")
}
// Compiling source remains possible while registration is pending. No distribution task may bypass the gate.
tasks.matching { it.name.startsWith("package") || it.name.endsWith("Distributable") }.configureEach {
    dependsOn(verifyWindowsOAuth)
}

compose.desktop.application {
    mainClass = "chat.bipe.desktop.ui.MainKt"
    // x64 source target only. Installer version mapping, signing, updates and ARM remain #1019 acceptance work.
    providers.environmentVariable("BIPE_NATIVE_DIR").orNull?.let { jvmArgs += "-Djna.library.path=$it" }
}
