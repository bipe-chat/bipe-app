// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.onboarding.BipePinReminderMemory

/**
 * The phone's own theft protection (bipe-chat/bipe#125, ADR 0045 tier 0, spec theft-protection). Android gives apps no
 * public API that reads Theft Detection Lock or Offline Device Lock, and no public intent for the Theft protection page
 * (it is part of Google Play services). So this code never shows a state and opens the deepest public settings page
 * on the path (security.md rule 14).
 */
object OsTheftProtection {
    private val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Spec theft-protection osPath: Samsung puts the page under Segurança e privacidade, the others under Google. */
    @get:StringRes
    val pathText: Int get() = if (isSamsung) R.string.bipe_os_theft_path_samsung else R.string.bipe_os_theft_path_android

    /** Action openOsSettings, target osTheftSettings. Falls back to the main settings page. */
    fun openSettings(context: Context) {
        val first = if (isSamsung) Settings.ACTION_SECURITY_SETTINGS else Settings.ACTION_SETTINGS
        for (action in listOf(first, Settings.ACTION_SETTINGS).distinct()) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
    }
}

/** What prompt osTheftProtection remembers. One flag for the phone, not per account: the protection belongs to the phone. */
class OsTheftPromptMemory(private val context: Context) {
    private val prefs = context.getSharedPreferences("bipe_os_theft_prompt", Context.MODE_PRIVATE)

    /** True after Conferir proteção or Agora não. The prompt never comes back on this phone. */
    val isDone: Boolean get() = prefs.getBoolean(DONE_KEY, false)

    fun markDone() {
        prefs.edit().putBoolean(DONE_KEY, true).apply()
    }

    /** Trigger voceAfterDays: this phone first saw the account [BipeOnboarding.OS_THEFT_PROTECTION_AFTER_DAYS] ago. */
    fun isDue(userId: String, now: Long = System.currentTimeMillis()): Boolean =
        now - BipePinReminderMemory(context, userId).firstSeen >= BipeOnboarding.OS_THEFT_PROTECTION_AFTER_DAYS * DAY

    private companion object {
        const val DONE_KEY = "done"
        const val DAY = 86_400_000L
    }
}

/** Spec theft-protection: the row Proteção do celular, above the guardians. It shows on every visit. */
@Composable
fun OsTheftProtectionRow() {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    Column {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.bipe_os_theft_row_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_os_theft_row_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            Text(stringResource(OsTheftProtection.pathText), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        SettingsRowItem(
            title = stringResource(R.string.bipe_os_theft_action),
            titleColor = colors.accentInk,
            onClick = { OsTheftProtection.openSettings(context) },
        )
    }
}
