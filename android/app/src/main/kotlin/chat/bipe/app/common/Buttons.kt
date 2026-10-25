// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The Material 3 buttons of the app. They are always at least 48 dp high, so that a button never
// shrinks to the height of its text (MZ, 25 October 2026). Material's own minimum is 40 dp.
// Use these, never Button, OutlinedButton, FilledTonalButton or ElevatedButton directly (tools/a11y.mjs).
// The calls below name the Material functions in full, so that the check passes them.

@Composable
fun BipeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(onClick, modifier.heightIn(min = BipeMinTapTarget), enabled, colors = colors, content = content)
}

@Composable
fun BipeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(onClick, modifier.heightIn(min = BipeMinTapTarget), enabled, colors = colors, content = content)
}

@Composable
fun BipeTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.FilledTonalButton(onClick, modifier.heightIn(min = BipeMinTapTarget), enabled, colors = colors, content = content)
}
