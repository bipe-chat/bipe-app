// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import java.util.Locale

/**
 * The app language choice of spec language-region. Android 13 and later keep it as the per-app language
 * (LocaleManager, res/xml/locales_config.xml). Android 8 to 12 have no per-app language: the app stores the choice
 * and [wrap] applies it to each activity.
 */
enum class AppLanguage(val id: String, val tag: String?, val title: Int) {
    SYSTEM("system", null, R.string.bipe_chats_appearance_system),
    PT("pt", "pt-BR", R.string.bipe_language_pt),
    EN("en", "en", R.string.bipe_language_en),
    ;

    companion object {
        private const val KEY = "app_language"

        fun current(context: Context): AppLanguage {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
                if (locales.isEmpty) return SYSTEM
                return if (locales[0].language == "en") EN else PT
            }
            val id = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.id == id } ?: SYSTEM
        }

        /** The name of the language the screens show now, also when the app follows the phone. */
        fun shownTitle(context: Context): Int = if (appLanguage(context) == "en") EN.title else PT.title

        /** Action selectLanguage: the screens change at once. The next bipe-api request sends the new language (ADR 0298). */
        fun select(activity: Activity, choice: AppLanguage) {
            if (choice == current(activity)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android stores it and recreates the activity.
                activity.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(choice.tag ?: "")
                return
            }
            activity.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).edit().putString(KEY, choice.id).commit()
            activity.recreate()
        }

        /** Android 8 to 12: the context of an activity in the stored language. Android 13 and later do this themselves. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = current(base).tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}

/** The account region of spec language-region (ADR 0197). With one region in registries/regions.json every account is in it. */
object AppRegion {
    val current: String get() = BipeRegistries.REGION_IDS.firstOrNull() ?: "br"

    fun title(context: Context, id: String): String {
        val res = context.resources.getIdentifier("bipe_region_$id", "string", context.packageName)
        return if (res == 0) id else context.getString(res)
    }
}

/** Spec language-region: the app language and the account region. */
@Composable
fun LanguageRegionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val language = AppLanguage.current(context)
    SettingsPage(stringResource(R.string.bipe_settings_language_region), BipeScreen.LANGUAGE_REGION.id, onBack) {
        item(key = "language-header") { SectionHeader(stringResource(R.string.bipe_settings_language)) }
        items(AppLanguage.entries, key = { "language-${it.id}" }) { choice ->
            CheckedRow(stringResource(choice.title), checked = choice == language, onClick = {
                view.bipeHaptic(BipeHaptic.Tick)
                activity?.let { AppLanguage.select(it, choice) }
            })
        }
        item(key = "language-footer") { Footer(stringResource(R.string.bipe_settings_language_footer)) }
        item(key = "region-header") { SectionHeader(stringResource(R.string.bipe_settings_region)) }
        // Action selectRegion does nothing while there is one region: the row is not a button.
        items(BipeRegistries.REGION_IDS, key = { "region-$it" }) { id ->
            CheckedRow(AppRegion.title(context, id), checked = id == AppRegion.current, onClick = null)
        }
        item(key = "region-footer") { Footer(stringResource(R.string.bipe_settings_region_footer)) }
    }
}

@Composable
private fun CheckedRow(title: String, checked: Boolean, onClick: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsRowItem(title = title, selected = checked, onClick = onClick)
        }
        if (checked) {
            Icon(
                painterResource(PhosphorIcon.CHECK.drawable),
                contentDescription = null,
                tint = BipeTheme.colors.accentInk,
                modifier = Modifier.padding(end = 16.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BipeTheme.colors.inkSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
