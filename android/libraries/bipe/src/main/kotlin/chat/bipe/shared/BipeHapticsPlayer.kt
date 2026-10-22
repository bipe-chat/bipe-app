// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.os.Build
import android.view.View
import chat.bipe.shared.generated.BipeHaptic

/**
 * Plays a haptic of the generated [BipeHaptic] (ADR 0134, registries/haptics.json): the first constant this Android
 * version knows. The only place in the app that calls performHapticFeedback. The system setting wins
 * (design/haptics.md rule 6).
 */
fun View.bipeHaptic(haptic: BipeHaptic) {
    val constant = haptic.constants.firstOrNull { Build.VERSION.SDK_INT >= it.minSdk } ?: return
    performHapticFeedback(constant.constant)
}
