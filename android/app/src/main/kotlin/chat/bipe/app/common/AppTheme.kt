// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.bipe.app.MainActivity
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipePalette

/**
 * Spec app-colors: the palette the user picked, kept on the phone. BipeTheme reads [palette], so a change redraws
 * the app at once. The launcher icon is the activity-alias of the palette (AndroidManifest.xml).
 */
object AppTheme {
    const val PALETTE_KEY = "palette"

    var palette by mutableStateOf(BipePalette.DEFAULT)
        private set

    /** Settings chats.appearance (bipe-chat/bipe#676): system, light or dark for the whole app, on this phone. */
    enum class Appearance(val id: String, val title: Int) {
        SYSTEM("system", R.string.bipe_chats_appearance_system),
        LIGHT("light", R.string.bipe_chats_appearance_light),
        DARK("dark", R.string.bipe_chats_appearance_dark),
        ;

        companion object {
            fun of(id: String?): Appearance = entries.firstOrNull { it.id == id } ?: SYSTEM
        }
    }

    var appearance by mutableStateOf(Appearance.SYSTEM)
        private set

    fun setAppearance(id: String) {
        appearance = Appearance.of(id)
    }

    /** Shows the stored palette. A missing or unknown id is the default palette. Call in Application.onCreate. */
    fun load(context: Context) {
        palette = BipePalette.fromId(prefs(context).getString(PALETTE_KEY, null))
        appearance = Appearance.of(prefs(context).getString(chat.bipe.shared.generated.BipeSettingsRow.CHATS_APPEARANCE.id, null))
    }

    /** Keeps the palette, shows it and switches the launcher icon to it. */
    fun save(context: Context, palette: BipePalette) {
        prefs(context).edit().putString(PALETTE_KEY, palette.id).apply()
        this.palette = palette
        applyLauncherIcon(context, palette)
    }

    /**
     * Enables the alias of the palette first, then disables the others, so the launcher always has one entry.
     * DONT_KILL_APP keeps the app open. Some launchers move or drop the home screen icon after the change.
     */
    private fun applyLauncherIcon(context: Context, palette: BipePalette) {
        val manager = context.packageManager
        val ordered = listOf(palette) + BipePalette.entries.filter { it != palette }
        for (entry in ordered) {
            val state = if (entry == palette) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            // A failed change keeps the theme and leaves the icon as it was (spec app-colors notes).
            runCatching { manager.setComponentEnabledSetting(alias(context, entry), state, PackageManager.DONT_KILL_APP) }
        }
    }

    /** The manifest alias .Launcher<Palette> next to MainActivity. */
    private fun alias(context: Context, palette: BipePalette): ComponentName {
        val packageName = MainActivity::class.java.name.substringBeforeLast('.')
        return ComponentName(context, "$packageName.Launcher${palette.id.replaceFirstChar { it.uppercase() }}")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
}

/** The generated launcher icon of a palette (@mipmap/bipe_app_icon_<palette>, libraries/bipe). */
val BipePalette.launcherIcon: Int
    get() = when (this) {
        BipePalette.ACAI -> R.mipmap.bipe_app_icon_acai
        BipePalette.PITANGA -> R.mipmap.bipe_app_icon_pitanga
        BipePalette.CAJU -> R.mipmap.bipe_app_icon_caju
        BipePalette.JABUTICABA -> R.mipmap.bipe_app_icon_jabuticaba
        BipePalette.JENIPAPO -> R.mipmap.bipe_app_icon_jenipapo
    }
