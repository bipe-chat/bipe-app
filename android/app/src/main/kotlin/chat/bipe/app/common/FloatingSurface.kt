// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** A control that floats over content: a solid surface with a hairline and a soft shadow (spec composer). */
fun Modifier.floatingSurface(shape: Shape, fill: Color, line: Color): Modifier =
    shadow(6.dp, shape, clip = false).background(fill, shape).border(1.dp, line, shape)
