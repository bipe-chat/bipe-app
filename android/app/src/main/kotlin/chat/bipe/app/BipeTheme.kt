// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
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
 */
@Composable
fun BipeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val highContrast = highContrast(LocalContext.current)
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
        )
    }
    val reduceMotion = reduceMotion(LocalContext.current)
    CompositionLocalProvider(LocalBipeColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// UiModeManager.getContrast() is 0 for the default contrast and up to 1 for high contrast (API 34).
private fun highContrast(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return false
    return uiModeManager.contrast >= 0.5f
}
