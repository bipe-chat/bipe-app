// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import java.io.File

/** Windows path of ADR 0238 (Kotlin on the JVM): the DLL through JNA. Argument: the path of badges.json. */
fun main(args: Array<String>) {
    println(BadgeHarness.run(File(args[0]).readText()))
}
