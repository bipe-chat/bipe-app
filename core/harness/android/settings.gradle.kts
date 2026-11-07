// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

rootProject.name = "bipe-core-android-harness"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("../../../android/vendor/maven") }
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../android/gradle/libs.versions.toml"))
        }
    }
}

include(":bipecore", ":harness", ":bipe")
// The current verifier, for the time comparison on the same emulator.
project(":bipe").projectDir = file("../../../android/libraries/bipe")
