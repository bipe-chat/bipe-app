// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Nunito, bundled as one variable font (res/font/nunito.ttf, SIL OFL 1.1, ADR 0159). One entry per weight sets
 * the wght axis, so each Material weight draws at its own weight, not a synthesized bold.
 */
@OptIn(ExperimentalTextApi::class)
private val Nunito = FontFamily(
    listOf(300, 400, 500, 600, 700, 800, 900).map { weight ->
        Font(
            R.font.nunito,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

private fun TextStyle.nunito() = copy(fontFamily = Nunito)

/**
 * Rounded type for all app UI text (ADR 0159): the Material 3 type scale (sizes, weights, line heights) with
 * Nunito as the face. Font scale still applies. `FontFamily.Monospace` on codes overrides it.
 */
internal val BipeTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.nunito(),
        displayMedium = displayMedium.nunito(),
        displaySmall = displaySmall.nunito(),
        headlineLarge = headlineLarge.nunito(),
        headlineMedium = headlineMedium.nunito(),
        headlineSmall = headlineSmall.nunito(),
        titleLarge = titleLarge.nunito(),
        titleMedium = titleMedium.nunito(),
        titleSmall = titleSmall.nunito(),
        bodyLarge = bodyLarge.nunito(),
        bodyMedium = bodyMedium.nunito(),
        bodySmall = bodySmall.nunito(),
        labelLarge = labelLarge.nunito(),
        labelMedium = labelMedium.nunito(),
        labelSmall = labelSmall.nunito(),
    )
}
