// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.os.Build
import android.view.View
import chat.bipe.shared.generated.BipeHaptic

/** Plays a Bipe haptic (ADR 0134): the first constant this Android version has. */
fun View.bipeHaptic(haptic: BipeHaptic) {
    val constant = haptic.constants.firstOrNull { Build.VERSION.SDK_INT >= it.minSdk } ?: return
    performHapticFeedback(constant.constant)
}
