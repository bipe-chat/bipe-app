// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the system setting "Remove animations" is on (bipe-chat/bipe#496). Bipe animations then end in
 * their final state at once: nothing pulses, slides or scrolls by itself. BipeTheme provides it.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** "Remove animations" sets the animator duration scale to 0. */
fun reduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The spec to animate with, or a jump to the end when Reduce Motion is on. */
@Composable
@ReadOnlyComposable
fun <T> bipeMotion(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalReduceMotion.current) snap() else spec
