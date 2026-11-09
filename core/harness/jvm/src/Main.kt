// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import java.io.File

/**
 * Windows path of ADR 0238 (Kotlin on the JVM): the DLL through JNA. Arguments: the path of badges.json, then the
 * path of core/harness/ed25519-cases.json. badge-issuers.json and theft-marks.json are read next to badges.json.
 */
fun main(args: Array<String>) {
    val badges = File(args[0])
    println(BadgeHarness.run(badges.readText()))
    println(IssuerTheftHarness.run(badges.readText(), badges.resolveSibling("badge-issuers.json").readText(), badges.resolveSibling("theft-marks.json").readText()))
    println(Ed25519Cases.run(File(args[1]).readText()))
}
