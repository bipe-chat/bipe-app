// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import chat.bipe.app.BipeTypography
import chat.bipe.desktop.settings.AppearancePhase
import java.awt.Dimension
import java.awt.Toolkit
import java.beans.PropertyChangeListener

fun main() = application {
    val catalog = remember { DesktopCatalog.load() }
    val model = remember { DesktopAppModel(catalog) }
    val phase by model.phase.collectAsState()
    val palette = when (val current = phase) {
        is AppPhase.Ready -> (current.appearance.phase.collectAsState().value as? AppearancePhase.Ready)?.values
            ?.palette(catalog.appearancePolicy) ?: catalog.defaultPalette
        else -> catalog.defaultPalette
    }
    val deviceAppearance by model.deviceAppearance.phase.collectAsState()
    Window(onCloseRequest = { model.closeWindow(::exitApplication) }, title = "Bipe",
           icon = painterResource("bipe-icon-${palette}.png"), state = rememberWindowState(width = 1180.dp, height = 800.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 600) }
        val dark = when (deviceAppearance.mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
        val highContrast = windowsHighContrast()
        val colors = remember(catalog, palette, dark, highContrast) { DesktopColors(catalog, palette, dark, highContrast) }
        val scheme = if (dark) darkColorScheme() else lightColorScheme()
        MaterialTheme(colorScheme = scheme.copy(
            primary = colors["accent.fill"], onPrimary = colors["ink.onAccent"],
            secondary = colors["accent.ink"], surface = colors["surface.base"], onSurface = colors["ink.primary"],
            background = colors["surface.base"], onBackground = colors["ink.primary"],
            surfaceVariant = colors["surface.raised"], onSurfaceVariant = colors["ink.secondary"],
            outline = colors["line.strong"], error = colors["trust.danger.ink"],
        ), typography = BipeTypography) { DesktopApp(model, phase, colors) }
    }
}

class DesktopColors(private val catalog: DesktopCatalog, val palette: String, val dark: Boolean, val highContrast: Boolean) {
    operator fun get(role: String) = catalog.color(role, palette, dark, highContrast)
    fun qr(role: String) = catalog.color(role, catalog.defaultPalette, dark = false, highContrast = true)
}

@Composable private fun windowsHighContrast(): Boolean {
    val toolkit = remember { Toolkit.getDefaultToolkit() }
    var enabled by remember { mutableStateOf(toolkit.getDesktopProperty("win.highContrast.on") == true) }
    DisposableEffect(toolkit) {
        val listener = PropertyChangeListener { enabled = it.newValue == true }
        toolkit.addPropertyChangeListener("win.highContrast.on", listener)
        onDispose { toolkit.removePropertyChangeListener("win.highContrast.on", listener) }
    }
    return enabled
}
