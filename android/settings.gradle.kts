// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Kotlin half of rustls-platform-verifier ships only inside its crate (bipe-chat/bipe#626).
        maven {
            url = uri(settingsDir.resolve("vendor/maven"))
            content { includeGroup("rustls") }
        }
    }
}

rootProject.name = "bipe"

include(":app")
include(":libraries:bipe")
include(":libraries:phosphor")
