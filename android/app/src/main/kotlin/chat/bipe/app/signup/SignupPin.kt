// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.Manifest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import chat.bipe.app.common.bipeMotion
import chat.bipe.app.common.PinHaptics
import chat.bipe.app.common.bipeHaptic
import androidx.compose.ui.platform.LocalView
import chat.bipe.shared.generated.BipeHaptic
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import chat.bipe.app.common.appVersionDisplay
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.AppConfig
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.app.linkcomputer.QrScanner
import chat.bipe.app.settings.openPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeInviteLink
import chat.bipe.shared.BipeLegalLinks
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingStart
import chat.bipe.shared.generated.BipeOnboardingStep
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen

// signup-pin

@Composable
internal fun SignupPinScreen(model: SignupFlowModel, confirm: Boolean) {
    val colors = BipeTheme.colors
    val pin by model.pin.collectAsState()
    val problem by model.pinProblem.collectAsState()
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(confirm) { focus.requestFocus() }
    OnboardingStepLayout(
        BipeScreen.SIGNUP_PIN,
        PhosphorIcon.LOCK_SIMPLE,
        stringResource(if (confirm) R.string.bipe_pin_confirm_title else R.string.bipe_pin_create_title),
        step = 2,
        message = if (confirm) null else stringResource(R.string.bipe_pin_create_body, BipeRegistries.PIN_LENGTH.toString()),
    ) {
        val progress = stringResource(R.string.bipe_a11y_pin_progress, pin.length.toString(), BipeRegistries.PIN_LENGTH.toString())
        val isError = problem != SignupFlowModel.PinProblem.NONE
        // The system digit keyboard (spec: the fallback of the number pad). It brings delete and paste.
        BasicTextField(
            value = pin,
            onValueChange = model::editPin,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.surfaceBase),
            cursorBrush = SolidColor(colors.surfaceBase),
            // TalkBack reads how many digits are in; the cells show it by their dot (bipe#495).
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { stateDescription = progress },
            decorationBox = { inner ->
                Box {
                    // The field itself stays one dot wide: the cells show how many digits are in.
                    Box(Modifier.size(1.dp)) { inner() }
                    PinCells(pin.length, focused, isError)
                }
            },
        )
        val problemText = when (problem) {
            SignupFlowModel.PinProblem.NONE -> null
            SignupFlowModel.PinProblem.REPEATED_DIGIT -> stringResource(R.string.bipe_pin_problem_repeated_digit)
            SignupFlowModel.PinProblem.SEQUENCE -> stringResource(R.string.bipe_pin_problem_sequence)
            SignupFlowModel.PinProblem.DATE -> stringResource(R.string.bipe_pin_problem_date)
            SignupFlowModel.PinProblem.MISMATCH -> stringResource(R.string.bipe_pin_mismatch)
        }
        if (problemText != null) OnboardingFieldFooter(problemText, isError = true)
        PinHaptics(pin.length, error = isError)
        OnboardingNote(PhosphorIcon.LOCK_SIMPLE, stringResource(R.string.bipe_pin_never_asked), filled = true)
    }
}

/**
 * One cell per digit, in two groups of four so the eight digits read in halves. A filled cell holds an açaí dot, the
 * next cell has the açaí ring while the keyboard is up. Same as iOS PinCells.
 */
@Composable
private fun PinCells(count: Int, focused: Boolean, isError: Boolean) {
    val colors = BipeTheme.colors
    val half = BipeRegistries.PIN_LENGTH / 2
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        listOf(0 until half, half until BipeRegistries.PIN_LENGTH).forEach { range ->
            Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                range.forEach { index ->
                    val filled = index < count
                    val current = focused && index == count
                    val shape = RoundedCornerShape(12.dp)
                    val dot by animateFloatAsState(if (filled) 1f else 0f, bipeMotion(spring(dampingRatio = 0.45f)), label = "pin")
                    Box(
                        Modifier
                            .weight(1f, fill = false)
                            .widthIn(min = 28.dp, max = 44.dp)
                            .height(52.dp)
                            .background(if (filled) colors.accentWash else colors.surfaceRaised, shape)
                            .border(
                                if (current || isError) 2.dp else 1.dp,
                                when {
                                    isError -> colors.trustDangerLine
                                    current -> colors.accentFill
                                    filled -> colors.accentFill.copy(alpha = 0.35f)
                                    else -> colors.lineSubtle
                                },
                                shape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).scale(dot).background(colors.accentFill, CircleShape))
                    }
                }
            }
        }
    }
}

// signup-fallback

@Composable
internal fun SignupFallbackScreen(model: SignupFlowModel) {
    val phase by model.fallbackPhase.collectAsState()
    val email by model.fallbackEmail.collectAsState()
    val invalid by model.fallbackEmailInvalid.collectAsState()
    val linkProvider by model.fallbackLinkProvider.collectAsState()
    LaunchedEffect(model) { model.loadFallbackLink() }
    val step = BipeOnboardingStep.FALLBACK
    OnboardingStepLayout(
        BipeScreen.SIGNUP_FALLBACK,
        PhosphorIcon.KEY,
        stringResource(step.title),
        step = 3,
        message = step.body?.let { stringResource(it) },
        actions = {
            if (phase == SignupFlowModel.FallbackPhase.CHOOSE) {
                OnboardingPrimaryButton(stringResource(step.primary.title), onClick = model::addEmail)
                // ADR 0096: the platform's own provider next to the e-mail, when the server offers it.
                if (linkProvider != null) {
                    Spacer(Modifier.height(8.dp))
                    OnboardingSecondaryButton(stringResource(R.string.bipe_link_google), onClick = model::linkAccount)
                }
            } else {
                OnboardingPrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::submitEmail)
            }
            step.text.forEach { OnboardingTextButton(stringResource(it.title), onClick = model::skipEmail) }
        },
    ) {
        if (phase != SignupFlowModel.FallbackPhase.CHOOSE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BipeField(stringResource(R.string.bipe_signup_email_optional), email, model::editFallbackEmail, KeyboardType.Email, icon = PhosphorIcon.KEY, isError = invalid)
                OnboardingFieldFooter(
                    stringResource(if (invalid) R.string.bipe_signup_email_invalid else R.string.bipe_signup_email_optional_footer),
                    isError = invalid,
                )
            }
        }
    }
}

// signup-create

@Composable
internal fun SignupCreateScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val phase by model.createPhase.collectAsState()
    val error by model.createError.collectAsState()
    Box(Modifier.fillMaxSize().testTag(BipeScreen.SIGNUP_CREATE.id)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            if (phase == SignupFlowModel.CreatePhase.FAILED) {
                Box(Modifier.size(72.dp).background(colors.trustDangerWash, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(36.dp))
                }
                // error is read so the text follows it.
                Text(
                    if (error == SignupFlowModel.CreateError.None) "" else model.createErrorText(),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                if (model.canFix) {
                    OnboardingPrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::fix)
                } else {
                    OnboardingPrimaryButton(stringResource(R.string.bipe_app_retry), onClick = model::retryCreate)
                }
            } else {
                OnboardingBusyMark()
                Text(
                    stringResource(R.string.bipe_app_signup_creating),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
