// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Bipe's mark, the shape of the app icon: a rounded bubble whose top-start corner stays tight as the notch
 * (identity.md, artwork-brief.md "seal tail"). The radii are 196 and 20 of a 528-point bubble, as on iOS
 * BipeMark. Use this shape for the brand; never a generic speech-bubble glyph.
 */
val BipeMarkShape = RoundedCornerShape(topStartPercent = 4, topEndPercent = 37, bottomEndPercent = 37, bottomStartPercent = 37)
