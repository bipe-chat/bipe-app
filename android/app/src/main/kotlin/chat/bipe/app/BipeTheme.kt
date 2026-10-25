// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import chat.bipe.app.common.LocalReduceMotion
import chat.bipe.app.common.reduceMotion
import chat.bipe.shared.generated.BipeColors
import chat.bipe.shared.generated.BipeDarkColors
import chat.bipe.shared.generated.BipeDarkHighContrastColors
import chat.bipe.shared.generated.BipeLightColors
import chat.bipe.shared.generated.BipeLightHighContrastColors

private val LocalBipeColors = staticCompositionLocalOf { BipeLightColors }

/** The Bipe colour tokens of the current theme. */
object BipeTheme {
    val colors: BipeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBipeColors.current
}

/**
 * Picks the token set from the system dark mode and, on Android 14 and later, the system contrast
 * setting. Material components get the same colours. No dynamic colour (identity.md: always açaí).
 * Every M3 surface role maps to a Bipe surface token, so no baseline (purple-tinted) default leaks into
 * lists, menus, sheets or fields; surfaceTint is the base surface, so elevation adds no tonal tint.
 */
@Composable
fun BipeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val highContrast = rememberHighContrast()
    val colors = when {
        dark && highContrast -> BipeDarkHighContrastColors
        dark -> BipeDarkColors
        highContrast -> BipeLightHighContrastColors
        else -> BipeLightColors
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accentFill,
            onPrimary = colors.inkOnAccent,
            background = colors.surfaceBase,
            onBackground = colors.inkPrimary,
            surface = colors.surfaceBase,
            onSurface = colors.inkPrimary,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.lineStrong,
            outlineVariant = colors.lineSubtle,
            error = colors.trustDangerInk,
            surfaceTint = colors.surfaceBase,
            surfaceBright = colors.surfaceBase,
            surfaceDim = colors.surfaceSunken,
            surfaceVariant = colors.surfaceSunken,
            surfaceContainerLowest = colors.surfaceBase,
            surfaceContainerLow = colors.surfaceRaised,
            surfaceContainer = colors.surfaceRaised,
            surfaceContainerHigh = colors.surfaceRaised,
            surfaceContainerHighest = colors.surfaceSunken,
            secondaryContainer = colors.accentWash,
            onSecondaryContainer = colors.accentInk,
        )
    } else {
        lightColorScheme(
            primary = colors.accentFill,
            onPrimary = colors.inkOnAccent,
            background = colors.surfaceBase,
            onBackground = colors.inkPrimary,
            surface = colors.surfaceBase,
            onSurface = colors.inkPrimary,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.lineStrong,
            outlineVariant = colors.lineSubtle,
            error = colors.trustDangerInk,
            surfaceTint = colors.surfaceBase,
            surfaceBright = colors.surfaceBase,
            surfaceDim = colors.surfaceSunken,
            surfaceVariant = colors.surfaceSunken,
            surfaceContainerLowest = colors.surfaceBase,
            surfaceContainerLow = colors.surfaceRaised,
            surfaceContainer = colors.surfaceRaised,
            surfaceContainerHigh = colors.surfaceRaised,
            surfaceContainerHighest = colors.surfaceSunken,
            secondaryContainer = colors.accentWash,
            onSecondaryContainer = colors.accentInk,
        )
    }
    val reduceMotion = reduceMotion(LocalContext.current)
    CompositionLocalProvider(LocalBipeColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * The high-contrast tokens (bipe-chat/bipe#497) when the system contrast is above the default (API 34:
 * UiModeManager.getContrast() is 0 by default, up to 1) or when "High contrast text" is on. Follows changes
 * while the app runs.
 */
@Composable
private fun rememberHighContrast(): Boolean {
    val context = LocalContext.current
    var high by remember { mutableStateOf(highContrast(context)) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@DisposableEffect onDispose {}
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return@DisposableEffect onDispose {}
        val listener = UiModeManager.ContrastChangeListener { high = highContrast(context) }
        uiModeManager.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { uiModeManager.removeContrastChangeListener(listener) }
    }
    return high
}

private fun highContrast(context: Context): Boolean {
    // Settings.Secure "high_text_contrast_enabled": the system's High contrast text switch.
    val highText = Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return highText
    val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return highText
    return highText || uiModeManager.contrast > 0f
}
