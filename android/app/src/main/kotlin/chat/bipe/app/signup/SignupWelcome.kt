// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.AppConfig
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.app.common.appVersionDisplay
import chat.bipe.app.settings.openPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeLegalLinks
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboardingStart
import chat.bipe.shared.generated.BipeScreen

// onboarding-welcome

@Composable
internal fun OnboardingWelcomeScreen(model: SignupFlowModel, onCancel: (() -> Unit)?) {
    val colors = BipeTheme.colors
    LaunchedEffect(Unit) { model.appear() }
    // The window decides the layout, never the device.
    BoxWithConstraints(Modifier.fillMaxSize().testTag(BipeScreen.ONBOARDING_WELCOME.id)) {
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            // Wide (a tablet, a foldable, landscape): the cover is the left half of the whole window, so the
            // split meets the fold. This box draws edge to edge, so half its width is half the window.
            val half = maxWidth / 2
            Row(Modifier.fillMaxSize()) {
                WelcomeCover(model, onCancel, WindowInsetsSides.Vertical + WindowInsetsSides.Start, Modifier.fillMaxHeight().width(half))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WelcomeActions(model, Modifier.widthIn(max = 400.dp))
                }
            }
        } else {
            // Tall: the cover takes everything above the two buttons.
            // The buttons sit on a sheet with round top corners that overlaps the cover's lower edge.
            Column(Modifier.fillMaxSize()) {
                WelcomeCover(
                    model, onCancel, WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    Modifier.fillMaxWidth().weight(1f), bottomInset = WELCOME_SHEET_OVERLAP,
                )
                val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val overlap = WELCOME_SHEET_OVERLAP.roundToPx()
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) { placeable.place(0, -overlap) }
                        }
                        .shadow(16.dp, sheet, ambientColor = colors.accentFillPressed, spotColor = colors.accentFillPressed)
                        .background(colors.surfaceBase, sheet)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WelcomeActions(model, Modifier.widthIn(max = 480.dp))
                }
            }
        }
    }
}

/**
 * The full-bleed açaí cover of the spec: what Bipe is. Mark and wordmark, the title and learnMore
 * over a faint texture of Bipe marks (identity.md: Brazilian in texture, warm açaí, never flag colours). The colour runs
 * under the system bars; the text keeps the safe-drawing insets on [sides].
 */
@Composable
private fun WelcomeCover(
    model: SignupFlowModel,
    onCancel: (() -> Unit)?,
    sides: WindowInsetsSides,
    modifier: Modifier,
    bottomInset: Dp = 0.dp,
) {
    val colors = BipeTheme.colors
    val invite by model.invite.collectAsState()
    val context = LocalContext.current
    val start = model.startVariant
    val handle = invite?.inviterHandle
    val title = if (start == BipeOnboardingStart.INVITED && handle != null) {
        stringResource(start.title, "@$handle")
    } else {
        stringResource(start.title)
    }
    Box(modifier.background(Brush.linearGradient(listOf(colors.accentFill, colors.accentFillPressed)))) {
        BubbleTexture()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 28.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel), color = colors.inkOnAccent) }
            }
            // The horizontal lockup of artwork-brief.md: mark, then the lowercase wordmark.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp)
                        .shadow(8.dp, BipeMarkShape, ambientColor = colors.accentFillPressed, spotColor = colors.accentFillPressed)
                        .background(colors.inkOnAccent, BipeMarkShape),
                )
                Text(
                    stringResource(R.string.bipe_app_name).lowercase(),
                    style = MaterialTheme.typography.displayMedium,
                    fontSize = 52.sp,
                    letterSpacing = (-1).sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.inkOnAccent,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = colors.inkOnAccent)
            // Action learnMore: the site in a Custom Tab, so sign-up stays where it was.
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(colors.inkOnAccent.copy(alpha = 0.16f))
                    .clickable(role = Role.Button) { openPage(context, BipeLegalLinks.homeUrl(AppConfig.PUBLIC_SITE)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.bipe_onboarding_learn_more), style = MaterialTheme.typography.titleSmall, color = colors.inkOnAccent)
                Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.inkOnAccent, modifier = Modifier.size(14.dp))
            }
            // The build that runs, small and quiet, for testers (same text as bipe_help_version in Settings).
            Text(appVersionDisplay(context), style = MaterialTheme.typography.labelSmall, color = colors.inkOnAccent.copy(alpha = 0.6f))
        }
    }
}

/** Two choices of equal width: new here (the variant's filled button) or back to an existing account. */
@Composable
private fun WelcomeActions(model: SignupFlowModel, modifier: Modifier) {
    val colors = BipeTheme.colors
    val variant by model.variant.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (variant == SignupFlowModel.Variant.LOADING) {
            // Keeps the height of the two buttons, so the cover does not jump when the variant arrives.
            Box(Modifier.fillMaxWidth().height(OnboardingMetrics.buttonHeight * 2 + 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accentFill)
            }
        } else {
            val start = model.startVariant
            val checking by model.checkingInvite.collectAsState()
            OnboardingPrimaryButton(stringResource(start.primary.title), busy = checking, onClick = model::primary)
            // haveAccount: recovery-start (bipe-chat/bipe#556).
            start.text.forEach { OnboardingSecondaryButton(stringResource(it.title), onClick = model.onHaveAccount) }
        }
    }
}

/** How far the welcome button sheet reaches over the cover. */
private val WELCOME_SHEET_OVERLAP = 28.dp

/** A Bipe mark of the cover texture: centre and side as fractions of the panel, so the texture follows the window size. */
private class TextureBubble(val x: Float, val y: Float, val size: Float, val opacity: Float)

/** The same four marks as iOS OnboardingWelcomeView. */
private val textureBubbles = listOf(
    TextureBubble(x = 0.86f, y = 0.22f, size = 0.50f, opacity = 0.12f),
    TextureBubble(x = 0.42f, y = 0.36f, size = 0.20f, opacity = 0.07f),
    TextureBubble(x = 1.00f, y = 0.66f, size = 0.30f, opacity = 0.09f),
    TextureBubble(x = 0.62f, y = 0.44f, size = 0.10f, opacity = 0.12f),
)

/** Bipe marks at low contrast over the cover: the bubble-and-notch motif of identity.md, not a pattern of objects. */
@Composable
private fun BubbleTexture() {
    val ink = BipeTheme.colors.inkOnAccent
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().clearAndSetSemantics {}) {
        val side = min(maxWidth, maxHeight * 1.4f)
        textureBubbles.forEach { bubble ->
            val size = side * bubble.size
            // Upright only: the notch stays top-start, as on the icon. Absolute positions, as on iOS.
            Box(
                Modifier
                    .absoluteOffset(x = maxWidth * bubble.x - size / 2, y = maxHeight * bubble.y - size / 2)
                    .requiredSize(size)
                    .background(ink.copy(alpha = bubble.opacity), BipeMarkShape),
            )
        }
    }
}
