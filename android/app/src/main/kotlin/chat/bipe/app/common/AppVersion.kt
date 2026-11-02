// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.Context
import android.os.Build
import chat.bipe.app.BuildConfig

/** The version name alone (version.json, ADR 0163), for the Ajuda e sobre summary in settings-root. */
fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

/** The version name, build and commit, for bipe_help_version and onboarding-welcome: "26.10.2 (141) 5481bba" (tools/version.mjs). */
fun appVersionDisplay(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    val commit = BuildConfig.BIPE_COMMIT
    return if (commit.isEmpty()) "${info.versionName} ($build)" else "${info.versionName} ($build) $commit"
}
