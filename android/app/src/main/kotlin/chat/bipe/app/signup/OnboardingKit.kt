// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.app.common.LocalReduceMotion
import chat.bipe.app.common.bipeMotion
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.generated.BipeScreen

// The look of the sign-up flow (bipe-chat/bipe#1308): one set of pieces for every step, so that each screen
// carries the Bipe brand of onboarding-welcome. The iOS twin is Signup/OnboardingKit.swift. Colours are tokens.

internal object OnboardingMetrics {
    /** The height of a filled or tonal button. Larger than the 48 dp minimum: these are the screen's main choices. */
    val buttonHeight = 56.dp
    val buttonShape = RoundedCornerShape(16.dp)
    val fieldShape = RoundedCornerShape(16.dp)
    val tileShape = RoundedCornerShape(18.dp)

    /** The steps that the progress bar counts: name, PIN, fallback (spec flow signup). */
    const val PROGRESS_STEPS = 3
}

// Buttons

/** The one filled button of a step: açaí, tall, with a soft açaí shadow and a press state. */
@Composable
internal fun OnboardingPrimaryButton(title: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled || busy
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, bipeMotion(spring()), label = "press")
    val lift by animateDpAsState(if (pressed || !active) 0.dp else 8.dp, bipeMotion(spring()), label = "lift")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OnboardingMetrics.buttonHeight)
            .scale(scale)
            .shadow(lift, OnboardingMetrics.buttonShape, ambientColor = colors.accentFill, spotColor = colors.accentFill)
            .background(
                when {
                    !active -> colors.surfaceSunken
                    pressed -> colors.accentFillPressed
                    else -> colors.accentFill
                },
                OnboardingMetrics.buttonShape,
            )
            .clickable(interaction, indication = null, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) colors.inkOnAccent else colors.inkDisabled,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The second choice next to a filled button: same size, açaí wash with an açaí edge, an optional glyph. */
@Composable
internal fun OnboardingSecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: PhosphorIcon? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = BipeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, bipeMotion(spring()), label = "press")
    val ink = if (enabled) colors.accentInk else colors.inkDisabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OnboardingMetrics.buttonHeight)
            .scale(scale)
            .background(colors.accentWash, OnboardingMetrics.buttonShape)
            .background(if (pressed) colors.accentFill.copy(alpha = 0.12f) else colors.accentWash.copy(alpha = 0f), OnboardingMetrics.buttonShape)
            .border(1.5.dp, colors.accentFill.copy(alpha = if (enabled) 0.28f else 0.1f), OnboardingMetrics.buttonShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(painterResource(icon.drawable), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink, textAlign = TextAlign.Center)
    }
}

/** A quiet choice under the filled button: açaí text in a full-width row, at least 48 dp high. */
@Composable
internal fun OnboardingTextButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) BipeTheme.colors.accentInk else BipeTheme.colors.inkDisabled,
            textAlign = TextAlign.Center,
        )
    }
}

// Step frame

/**
 * The backdrop of every step: an açaí wash that fades into the page, with one large faint Bipe mark at the top end
 * edge, the motif of the welcome cover.
 */
@Composable
internal fun OnboardingBackdrop(modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surfaceBase).clipToBounds().clearAndSetSemantics {}) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(minOf(320.dp, maxHeight * 0.45f))
                .background(Brush.verticalGradient(listOf(colors.accentWash, colors.surfaceBase))),
        )
        // Upright only: the notch stays top-start, as on the icon. Absolute, as on iOS.
        Box(
            Modifier
                .absoluteOffset(x = maxWidth - 150.dp, y = (-40).dp)
                .requiredSize(220.dp)
                .background(colors.accentFill.copy(alpha = 0.07f), BipeMarkShape),
        )
    }
}

/** The header of a step: a rounded tile with the step's glyph and, on the three typing steps, a progress bar. */
@Composable
internal fun OnboardingStepHeader(icon: PhosphorIcon, step: Int? = null) {
    val colors = BipeTheme.colors
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        // Never the seal shape for a tile: only the seal means "verified" (identity.md, Icons).
        Box(
            Modifier
                .size(56.dp)
                .shadow(6.dp, OnboardingMetrics.tileShape, ambientColor = colors.accentFill, spotColor = colors.accentFill)
                .background(Brush.linearGradient(listOf(colors.accentFill, colors.accentFillPressed)), OnboardingMetrics.tileShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.inkOnAccent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f))
        if (step != null) OnboardingProgress(step)
    }
}

/** Three short bars: the done and current steps in açaí, the rest in a line colour. Decorative: the title names the step. */
@Composable
internal fun OnboardingProgress(step: Int) {
    val colors = BipeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (index in 1..OnboardingMetrics.PROGRESS_STEPS) {
            val width by animateDpAsState(if (index == step) 28.dp else 14.dp, bipeMotion(spring()), label = "progress")
            Box(Modifier.width(width).height(6.dp).background(if (index <= step) colors.accentFill else colors.lineSubtle, CircleShape))
        }
    }
}

/**
 * A sign-up step: backdrop, header, title, optional body, content that scrolls, and an action bar pinned at the
 * bottom (above the keyboard: the flow pads for the IME).
 */
@Composable
internal fun OnboardingStepLayout(
    screen: BipeScreen,
    icon: PhosphorIcon,
    title: String,
    step: Int? = null,
    message: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BipeTheme.colors
    // SignupFlowScreen draws OnboardingBackdrop behind every step, under the system bars.
    Box(Modifier.fillMaxSize().testTag(screen.id)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OnboardingStepHeader(icon, step)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                }
                content()
            }
            if (actions != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to colors.surfaceBase.copy(alpha = 0f), 0.3f to colors.surfaceBase))
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions,
                )
            }
        }
    }
}

// Fields

@Composable
internal fun OnboardingFieldFooter(text: String, isError: Boolean = false) {
    val color = if (isError) BipeTheme.colors.trustDangerInk else BipeTheme.colors.inkSecondary
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        // The icon says "error" next to the colour (bipe#497: never colour alone).
        if (isError) {
            Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** A short line with an açaí glyph: a fact about the step (where data lives, what Bipe never asks). */
@Composable
internal fun OnboardingNote(icon: PhosphorIcon, text: String, filled: Boolean = false) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (filled) Modifier.background(colors.accentWash, RoundedCornerShape(16.dp)).padding(14.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

/** The clickwrap box: a rounded square with a check when on. */
@Composable
internal fun OnboardingCheckbox(checked: Boolean) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val checkScale by animateFloatAsState(if (checked) 1f else 0.3f, bipeMotion(spring(dampingRatio = 0.5f)), label = "check")
    Box(
        Modifier
            .size(26.dp)
            .background(if (checked) colors.accentFill else colors.surfaceRaised, shape)
            .border(2.dp, if (checked) colors.accentFill else colors.lineStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.inkOnAccent, modifier = Modifier.size(16.dp).scale(checkScale))
        }
    }
}

/** A card for a group of content on a step: raised, rounded, with a thin line. */
@Composable
internal fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised, shape).border(1.dp, colors.lineSubtle, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

// Progress

/** The branded wait state: the Bipe mark that breathes inside a soft açaí ring. Still when animations are off. */
@Composable
internal fun OnboardingBusyMark(size: Dp = 128.dp) {
    val colors = BipeTheme.colors
    val still = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "busy")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "breath")
    val t = if (still) 0.5f else phase
    Box(Modifier.size(size * 1.3f).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(0.92f + 0.16f * t).background(colors.accentWash, CircleShape))
        Box(
            Modifier.size(size).scale(1f + 0.25f * t).alpha(1f - t)
                .border(2.dp, colors.accentFill.copy(alpha = 0.25f), CircleShape),
        )
        Box(
            Modifier.size(size / 2).scale(0.96f + 0.08f * t)
                .background(Brush.linearGradient(listOf(colors.accentFill, colors.accentFillPressed)), BipeMarkShape),
        )
    }
}
