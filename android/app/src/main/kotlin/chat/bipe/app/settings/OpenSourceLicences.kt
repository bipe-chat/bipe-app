// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.BipeLicenceList
import chat.bipe.shared.settings.BipeLicensedComponent

/** assets/open_source_licences.json, made by tools/oss-licences.mjs from tools/licences.json. */
private fun readLicences(context: Context): BipeLicenceList? = runCatching {
    BipeLicenceList.parse(context.assets.open("open_source_licences.json").use { it.readBytes().decodeToString() })
}.getOrNull()

/**
 * Spec open-source-licences: every open source component of the app with its licence, Bipe first (ADR 0144). Component
 * names and licence texts are not translated.
 */
@Composable
fun OpenSourceLicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember { readLicences(context) }
    var selected by remember { mutableStateOf<BipeLicensedComponent?>(null) }
    val colors = BipeTheme.colors
    val open = selected
    if (open != null) {
        // Action back: closes the text.
        BackHandler { selected = null }
        SettingsPage(open.name, BipeScreen.OPEN_SOURCE_LICENCES.id, onBack = { selected = null }) {
            item(key = "text") {
                Text(
                    file?.textOf(open).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.inkPrimary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        return
    }
    SettingsPage(stringResource(R.string.bipe_help_licenses), BipeScreen.OPEN_SOURCE_LICENCES.id, onBack) {
        items(file?.components.orEmpty(), key = { it.name }) { component ->
            // Action select.
            SettingsRowItem(title = component.name, value = component.licence, showsChevron = true, onClick = { selected = component })
        }
    }
}
