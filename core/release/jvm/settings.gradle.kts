// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The JVM jar of a bipe-core release (bipe-chat/bipe#1277 item 4): the Kotlin bindings and the native library of each
// desktop platform, in JNA's resource layout. core.yml (job core-jvm-jar) builds it; core-release.yml publishes it.
rootProject.name = "bipe-core-jvm"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
