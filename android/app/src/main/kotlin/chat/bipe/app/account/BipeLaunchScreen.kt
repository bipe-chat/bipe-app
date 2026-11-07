// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import chat.bipe.app.common.AppTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeLaunchRing
import chat.bipe.shared.generated.BipePalette

/**
 * Spec account-start: the launch screen. The icon background of the stored palette, full bleed, one turning ring, no
 * text. The label is for screen readers only. The light system bar icons last while this screen shows.
 */
@Composable
fun BipeLaunchScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { bars?.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }
    val label = stringResource(R.string.bipe_app_start_loading)
    val turn by rememberInfiniteTransition(label = "launch").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(BipeLaunchRing.TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "launch-ring",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.palette.iconBackground)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(BipeLaunchRing.DIAMETER_DP.dp)) {
            val stroke = BipeLaunchRing.STROKE_DP.dp.toPx()
            drawArc(
                color = BipePalette.ICON_MARK,
                startAngle = turn,
                sweepAngle = 360f * BipeLaunchRing.ARC,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
