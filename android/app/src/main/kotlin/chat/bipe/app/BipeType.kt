// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * App UI type: the system face with the sizes and line heights of the iOS text styles at the default Dynamic Type
 * size, so a screen reads at the same size on both apps. The Material 3 defaults (14 sp body, wide tracking) read
 * small next to iOS. Font scale still applies. `FontFamily.Monospace` on codes overrides the face.
 */
private fun TextStyle.ios(size: Int, lineHeight: Int, weight: FontWeight = fontWeight ?: FontWeight.Normal, tracking: TextUnit = 0.em) =
    copy(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight, letterSpacing = tracking)

internal val BipeTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall.ios(36, 44, FontWeight.Bold),
        // largeTitle 34 bold, title1 28, title2 22, title3 20.
        headlineLarge = headlineLarge.ios(34, 41, FontWeight.Bold, (-0.01).em),
        headlineMedium = headlineMedium.ios(28, 34, FontWeight.Bold, (-0.01).em),
        headlineSmall = headlineSmall.ios(24, 30, FontWeight.SemiBold),
        titleLarge = titleLarge.ios(22, 28, FontWeight.SemiBold),
        // headline 17 semibold; subheadline 15.
        titleMedium = titleMedium.ios(17, 22, FontWeight.SemiBold),
        titleSmall = titleSmall.ios(15, 20, FontWeight.SemiBold),
        // body 17, subheadline 15, footnote 13.
        bodyLarge = bodyLarge.ios(17, 22),
        bodyMedium = bodyMedium.ios(15, 20),
        bodySmall = bodySmall.ios(13, 18),
        // Buttons 17 medium; caption1 12.
        labelLarge = labelLarge.ios(17, 22, FontWeight.Medium),
        labelMedium = labelMedium.ios(13, 18, FontWeight.Medium),
        labelSmall = labelSmall.ios(12, 16, FontWeight.Medium),
    )
}
