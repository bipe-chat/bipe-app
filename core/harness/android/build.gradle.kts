// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// :bipe takes bipe-core from its GitHub release (android/settings.gradle.kts), and this build has no repository
// for it. The harness tests the AAR of this run, so every project resolves that module to :bipecore.
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("chat.bipe.core:bipe-core")).using(project(":bipecore")).withoutArtifactSelectors()
        }
    }
}
