// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Android consumer of a bipe-core release (bipe-chat/bipe#1277 items 4, 5 and 8). Gradle takes the AAR from the
// GitHub release that ../release.json names, and gradle/verification-metadata.xml pins its SHA-256. The app the
// first client builds uses the same repository and pin (README.md).
rootProject.name = "bipe-core-release-android"

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
        // The release assets: core-<version>/bipe-core-<version>.aar.
        ivy {
            url = uri("https://github.com/bipe-chat/bipe-app/releases/download")
            patternLayout { artifact("core-[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("chat.bipe.core") }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../android/gradle/libs.versions.toml"))
        }
    }
}

include(":bench")
