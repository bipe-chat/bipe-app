// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui.common

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Same minimum control height as the phone's BipeButton; Windows keeps its standard focus/keyboard semantics. */
@Composable
fun BipeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    MaterialButton(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 48.dp)) { Text(label) }
}
