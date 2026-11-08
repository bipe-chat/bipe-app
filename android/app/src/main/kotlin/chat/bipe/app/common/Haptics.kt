// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import chat.bipe.shared.generated.BipeHaptic

/** Plays a Bipe haptic (ADR 0134): the first constant this Android version has. */
fun View.bipeHaptic(haptic: BipeHaptic) {
    val constant = haptic.constants.firstOrNull { Build.VERSION.SDK_INT >= it.minSdk } ?: return
    performHapticFeedback(constant.constant)
}

/**
 * Haptics of a PIN field (MZ, 8 Nov 2026: PIN inputs always have haptics): a tick for each digit typed or deleted,
 * the error haptic (pin_wrong) when [error] turns on. A reset or a paste moves more than one digit and does not tick.
 * The shared pad ticks on its own keys (PinPad.kt). Same as iOS bipePinHaptics.
 */
@Composable
fun PinHaptics(count: Int, error: Boolean = false) {
    val view = LocalView.current
    var last by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (abs(count - last) == 1) view.bipeHaptic(BipeHaptic.Tick)
        last = count
    }
    var wasError by remember { mutableStateOf(error) }
    LaunchedEffect(error) {
        if (error && !wasError) view.bipeHaptic(BipeHaptic.Error)
        wasError = error
    }
}
