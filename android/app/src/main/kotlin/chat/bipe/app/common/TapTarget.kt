// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The smallest tap target: 48 x 48 dp (bipe/docs/design/system.md, bipe-chat/bipe#498). */
val BipeMinTapTarget = 48.dp

/**
 * Reserves at least 48 x 48 dp to tap, whatever the size of the icon or text in it; the content
 * keeps its own size and sits in the middle. Put it first in the chain, before size and clickable.
 */
fun Modifier.bipeTapTarget(): Modifier = minimumInteractiveComponentSize()
