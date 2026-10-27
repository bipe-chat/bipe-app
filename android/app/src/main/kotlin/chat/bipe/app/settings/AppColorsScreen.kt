// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.AppTheme
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipePalette
import chat.bipe.shared.generated.BipeScreen

/** Spec app-colors: the user picks a palette. It sets the accent colours, the own bubble and the launcher icon at once. */
@Composable
fun AppColorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = BipeTheme.colors
    SettingsPage(stringResource(R.string.bipe_chats_colors), BipeScreen.APP_COLORS.id, onBack) {
        items(BipePalette.entries.size, key = { BipePalette.entries[it].id }) { index ->
            val palette = BipePalette.entries[index]
            val selected = AppTheme.palette == palette
            ListItem(
                leadingContent = { AppIconPreview(palette) },
                headlineContent = { Text(stringResource(palette.title), color = colors.inkPrimary) },
                trailingContent = if (!selected) null else {
                    {
                        Icon(
                            painterResource(PhosphorIcon.CHECK.drawable),
                            contentDescription = null,
                            tint = colors.accentInk,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
                // TalkBack reads a radio button and whether it is the chosen one; the check is not the only sign.
                modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = {
                    if (!selected) {
                        AppTheme.save(context, palette)
                        view.bipeHaptic(BipeHaptic.Tick)
                    }
                }),
            )
        }
        item(key = "footer") {
            Text(
                stringResource(R.string.bipe_chats_colors_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** The launcher icon of a palette: the mark on its icon background, as on the home screen. */
@Composable
private fun AppIconPreview(palette: BipePalette, side: Dp = 44.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(side).background(palette.iconBackground, RoundedCornerShape(percent = 22)),
    ) {
        // The mark is 33 of the icon's 64 units (bipe-shared design/tokens.json appThemes.icon).
        Box(Modifier.fillMaxSize().padding(side * 31 / 128).background(BipePalette.ICON_MARK, BipeMarkShape))
    }
}

/** The palette name, bipe_colors_<palette>. */
val BipePalette.title: Int
    get() = when (this) {
        BipePalette.ACAI -> R.string.bipe_colors_acai
        BipePalette.PITANGA -> R.string.bipe_colors_pitanga
        BipePalette.CAJU -> R.string.bipe_colors_caju
        BipePalette.JABUTICABA -> R.string.bipe_colors_jabuticaba
        BipePalette.JENIPAPO -> R.string.bipe_colors_jenipapo
    }
