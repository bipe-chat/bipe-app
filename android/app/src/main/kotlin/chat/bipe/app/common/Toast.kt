// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bipe's one toast (bipe/docs/design/toasts.md): a short result at the bottom of the screen for 3 seconds.
 * Any code calls [show]; the top [BipeToastHost] draws it. A dialog or full-screen sheet is its own window, so it
 * puts its own host inside and the toast shows above it.
 */
object BipeToasts {
    const val DURATION_MS = 3_000L

    class Shown internal constructor(val text: String, internal val at: Long)

    private val shown = MutableStateFlow<Shown?>(null)
    val current: StateFlow<Shown?> = shown.asStateFlow()

    /** The hosts on screen, oldest first. The last one draws the toast. */
    internal val hosts = mutableStateListOf<Any>()

    /** A new toast replaces the one on screen. */
    fun show(text: String) {
        shown.value = Shown(text, SystemClock.uptimeMillis())
    }

    fun show(context: Context, @StringRes text: Int) = show(context.getString(text))

    /** Text copied. Android 13 and later confirm a copy themselves, so Bipe adds a toast only before that. */
    fun copied(context: Context, @StringRes text: Int = R.string.bipe_app_copied) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) show(context, text)
    }

    internal fun dismiss(toast: Shown) {
        shown.compareAndSet(toast, null)
    }
}

/**
 * Draws [content] and the toast over it. [bottom] keeps the toast clear of the tab bar and the composer.
 * [colors] lets a screen with its own fixed colours (the media viewer is always dark) match the toast to them.
 */
@Composable
fun BipeToastHost(
    modifier: Modifier = Modifier,
    bottom: Dp = 88.dp,
    colors: BipeColors = BipeTheme.colors,
    content: @Composable BoxScope.() -> Unit,
) {
    val key = remember { Any() }
    DisposableEffect(key) {
        BipeToasts.hosts += key
        onDispose { BipeToasts.hosts -= key }
    }
    val toast by BipeToasts.current.collectAsState()
    val isTop = BipeToasts.hosts.lastOrNull() === key
    val visible = toast?.takeIf { isTop }

    LaunchedEffect(visible) {
        val shown = visible ?: return@LaunchedEffect
        delay((shown.at + BipeToasts.DURATION_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
        BipeToasts.dismiss(shown)
    }

    val reduceMotion = LocalReduceMotion.current
    val fade: FiniteAnimationSpec<Float> = if (reduceMotion) snap() else tween(200)
    val slide: FiniteAnimationSpec<IntOffset> = if (reduceMotion) snap() else tween(200)

    Box(modifier) {
        content()
        AnimatedContent(
            targetState = visible,
            transitionSpec = {
                (fadeIn(fade) + slideInVertically(slide) { it / 2 }) togetherWith (fadeOut(fade) + slideOutVertically(slide) { it / 2 })
            },
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = bottom, start = 24.dp, end = 24.dp),
            label = "toast",
        ) { shown ->
            if (shown != null) {
                Text(
                    shown.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .shadow(4.dp, RoundedCornerShape(50))
                        .background(colors.surfaceRaised, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
