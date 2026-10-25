// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.AppConfig
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.app.settings.openPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeLegalLinks
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingStart
import chat.bipe.shared.generated.BipeOnboardingStep
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen

/** The sign-up flow from onboarding-welcome to signup-create. account-start shows it when its phase is noAccount. */
@Composable
fun SignupFlowScreen(model: SignupFlowModel, onCancel: ((reachedCreate: Boolean) -> Unit)? = null) {
    val path by model.path.collectAsState()
    val showsWaitlist by model.showsWaitlist.collectAsState()
    BackHandler(enabled = path.isNotEmpty() && model.canGoBack) { model.back() }
    // Spec account-new: the sign-up from Nova conta starts at signup-name and closes from there.
    val cancelRow: @Composable () -> Unit = {
        if (onCancel != null) {
            TextButton(onClick = { onCancel(model.reachedCreate) }) { Text(stringResource(R.string.bipe_app_cancel)) }
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
        Box(Modifier.fillMaxSize().imePadding()) {
            when (val route = path.lastOrNull()) {
                // The welcome cover draws behind the status bar and pads its own content.
                null -> OnboardingWelcomeScreen(model, onCancel?.let { cancel -> { cancel(model.reachedCreate) } })
                else -> Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (route) {
                        SignupFlowModel.Route.INVITE_CODE -> SignupInviteCodeScreen(model)
                        SignupFlowModel.Route.NAME -> if (model.start != null && path.size == 1) {
                            Column {
                                cancelRow()
                                SignupNameScreen(model)
                            }
                            LaunchedEffect(Unit) { model.appear() }
                        } else {
                            SignupNameScreen(model)
                        }
                        SignupFlowModel.Route.PIN -> SignupPinScreen(model, confirm = false)
                        SignupFlowModel.Route.PIN_CONFIRM -> SignupPinScreen(model, confirm = true)
                        SignupFlowModel.Route.FALLBACK -> SignupFallbackScreen(model)
                        SignupFlowModel.Route.CREATE -> SignupCreateScreen(model)
                    }
                }
            }
        }
    }
    if (showsWaitlist) {
        Dialog(onDismissRequest = model::closeWaitlist) { SignupWaitlistScreen(model) }
    }
}

// Shared pieces

/** A sign-up step: title, optional body and content. */
@Composable
private fun StepLayout(screen: BipeScreen, title: String, message: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(screen.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        content()
    }
}

/** The one filled button of a step. */
@Composable
private fun PrimaryButton(title: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) colors.accentFill else colors.surfaceSunken, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.inkOnAccent else colors.inkDisabled)
        }
    }
}

/** A text button under the filled button. */
@Composable
private fun TextButtonRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = if (enabled) BipeTheme.colors.accentInk else BipeTheme.colors.inkDisabled,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun FieldFooter(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) BipeTheme.colors.trustDangerInk else BipeTheme.colors.inkSecondary)
}

@Composable
private fun BipeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val colors = BipeTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
        cursorBrush = SolidColor(colors.accentFill),
        modifier = modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                inner()
            }
        },
    )
}

// onboarding-welcome

@Composable
private fun OnboardingWelcomeScreen(model: SignupFlowModel, onCancel: (() -> Unit)?) {
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
            Column(Modifier.fillMaxSize()) {
                WelcomeCover(model, onCancel, WindowInsetsSides.Top + WindowInsetsSides.Horizontal, Modifier.fillMaxWidth().weight(1f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(24.dp),
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
private fun WelcomeCover(model: SignupFlowModel, onCancel: (() -> Unit)?, sides: WindowInsetsSides, modifier: Modifier) {
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
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel), color = colors.inkOnAccent) }
            }
            // The horizontal lockup of artwork-brief.md: mark, then the lowercase wordmark.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.inkOnAccent, BipeMarkShape))
                Text(
                    stringResource(R.string.bipe_app_name).lowercase(),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.inkOnAccent,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = colors.inkOnAccent)
            // Action learnMore: the site in a Custom Tab, so sign-up stays where it was.
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { openPage(context, BipeLegalLinks.homeUrl(AppConfig.PUBLIC_SITE)) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.bipe_onboarding_learn_more), style = MaterialTheme.typography.titleSmall, color = colors.inkOnAccent)
                Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.inkOnAccent, modifier = Modifier.size(14.dp))
            }
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
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accentFill)
            }
        } else {
            val start = model.startVariant
            val checking by model.checkingInvite.collectAsState()
            PrimaryButton(stringResource(start.primary.title), busy = checking, onClick = model::primary)
            // haveAccount: recovery-start (bipe-chat/bipe#556).
            start.text.forEach { SecondaryButton(stringResource(it.title), onClick = model.onHaveAccount) }
        }
    }
}

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

/** The second choice next to a filled button: same size, açaí wash. */
@Composable
private fun SecondaryButton(title: String, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentWash, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.accentInk)
    }
}

// signup-invite-code

@Composable
private fun SignupInviteCodeScreen(model: SignupFlowModel) {
    val code by model.code.collectAsState()
    val phase by model.inviteCodePhase.collectAsState()
    val error by model.inviteCodeError.collectAsState()
    val step = BipeOnboardingStep.INVITE_CODE
    StepLayout(BipeScreen.SIGNUP_INVITE_CODE, stringResource(step.title), step.body?.let { stringResource(it) }) {
        BipeField(stringResource(R.string.bipe_signup_invite_code), code, model::editCode)
        // Action pasteCode: reads the clipboard only on this tap and continues at once.
        TextButtonRow(stringResource(R.string.bipe_onboarding_paste_invite), enabled = phase != SignupFlowModel.InviteCodePhase.CHECKING, onClick = model::pasteCode)
        when {
            phase == SignupFlowModel.InviteCodePhase.CHECKING -> FieldFooter(stringResource(R.string.bipe_app_invite_checking))
            error != null -> FieldFooter(error.orEmpty(), isError = true)
        }
        PrimaryButton(stringResource(step.primary.title), enabled = code.isNotBlank(), busy = phase == SignupFlowModel.InviteCodePhase.CHECKING, onClick = model::submitCode)
        step.text.forEach { TextButtonRow(stringResource(it.title), onClick = model::noInvite) }
    }
}

// signup-waitlist

@Composable
private fun SignupWaitlistScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val email by model.waitlistEmail.collectAsState()
    val phase by model.waitlistPhase.collectAsState()
    val invalid by model.waitlistEmailInvalid.collectAsState()
    val error by model.waitlistError.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceBase, RoundedCornerShape(20.dp)).padding(24.dp)
            .testTag(BipeScreen.SIGNUP_WAITLIST.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(BipeOnboarding.waitlistBody), color = colors.inkPrimary)
        if (phase == SignupFlowModel.WaitlistPhase.DONE) {
            Text(stringResource(BipeOnboarding.waitlistDone), style = MaterialTheme.typography.titleMedium, color = colors.statusSuccess)
        } else {
            BipeField(stringResource(R.string.bipe_signup_email), email, model::editWaitlistEmail, KeyboardType.Email)
            when {
                invalid -> FieldFooter(stringResource(R.string.bipe_signup_email_invalid), isError = true)
                error != null -> FieldFooter(error.orEmpty(), isError = true)
            }
            PrimaryButton(stringResource(BipeOnboarding.waitlistPrimary.title), busy = phase == SignupFlowModel.WaitlistPhase.SENDING, onClick = model::joinWaitlist)
        }
        TextButtonRow(stringResource(R.string.bipe_app_close), onClick = model::closeWaitlist)
    }
}

// signup-name

@Composable
private fun SignupNameScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val name by model.displayName.collectAsState()
    val accepted by model.termsAccepted.collectAsState()
    val required by model.showTermsRequired.collectAsState()
    val invite by model.invite.collectAsState()
    val step = BipeOnboardingStep.NAME
    StepLayout(BipeScreen.SIGNUP_NAME, stringResource(step.title)) {
        val handle = invite?.takeIf { it.isValid }?.inviterHandle
        if (handle != null) {
            Text(stringResource(R.string.bipe_signup_invited_by, handle), style = MaterialTheme.typography.titleSmall, color = colors.accentInk)
        }
        BipeField(stringResource(if (model.isCompany) R.string.bipe_company_name else R.string.bipe_signup_display_name), name, model::editName)
        FieldFooter(stringResource(R.string.bipe_signup_display_name_footer))
        TermsRow(accepted, onToggle = model::toggleTerms)
        if (required) FieldFooter(stringResource(R.string.bipe_signup_terms_required), isError = true)
        PrimaryButton(stringResource(step.primary.title), enabled = model.canContinueName(name, accepted), onClick = model::continueFromName)
    }
}

/**
 * The clickwrap row (ADR 0113): each link text opens its page in the browser; a tap on the rest of the sentence flips the
 * box. signup-name and terms-reaccept show it.
 */
@Composable
fun TermsRow(accepted: Boolean, onToggle: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val terms = stringResource(R.string.bipe_signup_terms_link)
    val privacy = stringResource(R.string.bipe_signup_privacy_link)
    val sentence = stringResource(R.string.bipe_signup_terms_accept, terms, privacy)
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.accentInk, fontWeight = FontWeight.SemiBold))
    val text = remember(sentence, colors) {
        buildAnnotatedString {
            append(sentence)
            listOf(terms to BipeLegalLinks.termsUrl(AppConfig.PUBLIC_SITE), privacy to BipeLegalLinks.privacyUrl(AppConfig.PUBLIC_SITE))
                .forEach { (label, url) ->
                    val start = sentence.indexOf(label)
                    if (start >= 0) {
                        addLink(
                            LinkAnnotation.Clickable(label, linkStyle) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            start,
                            start + label.length,
                        )
                    }
                }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.clickable(role = Role.Checkbox, onClick = onToggle)
        .semantics { stateDescription = if (accepted) "✓" else "" }) {
        Box(
            Modifier.size(22.dp)
                .background(if (accepted) colors.accentFill else colors.surfaceBase, RoundedCornerShape(5.dp))
                .border(2.dp, if (accepted) colors.accentFill else colors.lineStrong, RoundedCornerShape(5.dp)),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary))
    }
}

// signup-pin

@Composable
private fun SignupPinScreen(model: SignupFlowModel, confirm: Boolean) {
    val colors = BipeTheme.colors
    val pin by model.pin.collectAsState()
    val problem by model.pinProblem.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(confirm) { focus.requestFocus() }
    StepLayout(
        BipeScreen.SIGNUP_PIN,
        stringResource(if (confirm) R.string.bipe_pin_confirm_title else R.string.bipe_pin_create_title),
        if (confirm) null else stringResource(R.string.bipe_pin_create_body, BipeRegistries.PIN_LENGTH.toString()),
    ) {
        val progress = stringResource(R.string.bipe_a11y_pin_progress, pin.length.toString(), BipeRegistries.PIN_LENGTH.toString())
        // The system digit keyboard (spec: the fallback of the number pad). It brings delete and paste.
        BasicTextField(
            value = pin,
            onValueChange = model::editPin,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.surfaceBase),
            cursorBrush = SolidColor(colors.surfaceBase),
            // TalkBack reads how many digits are in; the dots show it by colour only (bipe#495).
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { stateDescription = progress },
            decorationBox = { inner ->
                Box {
                    // The field itself stays one dot wide: the row of dots shows how many digits are in.
                    Box(Modifier.size(1.dp)) { inner() }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        repeat(BipeRegistries.PIN_LENGTH) { index ->
                            Box(Modifier.size(14.dp).background(if (index < pin.length) colors.accentFill else colors.surfaceSunken, CircleShape))
                        }
                    }
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
        if (problemText != null) FieldFooter(problemText, isError = true)
        FieldFooter(stringResource(R.string.bipe_pin_never_asked))
    }
}

// signup-fallback

@Composable
private fun SignupFallbackScreen(model: SignupFlowModel) {
    val phase by model.fallbackPhase.collectAsState()
    val email by model.fallbackEmail.collectAsState()
    val invalid by model.fallbackEmailInvalid.collectAsState()
    val linkProvider by model.fallbackLinkProvider.collectAsState()
    LaunchedEffect(model) { model.loadFallbackLink() }
    val step = BipeOnboardingStep.FALLBACK
    StepLayout(BipeScreen.SIGNUP_FALLBACK, stringResource(step.title), step.body?.let { stringResource(it) }) {
        if (phase == SignupFlowModel.FallbackPhase.CHOOSE) {
            PrimaryButton(stringResource(step.primary.title), onClick = model::addEmail)
            // ADR 0096: the platform's own provider next to the e-mail, when the server offers it.
            if (linkProvider != null) TextButtonRow(stringResource(R.string.bipe_link_google), onClick = model::linkAccount)
        } else {
            BipeField(stringResource(R.string.bipe_signup_email_optional), email, model::editFallbackEmail, KeyboardType.Email)
            FieldFooter(
                stringResource(if (invalid) R.string.bipe_signup_email_invalid else R.string.bipe_signup_email_optional_footer),
                isError = invalid,
            )
            PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::submitEmail)
        }
        step.text.forEach { TextButtonRow(stringResource(it.title), onClick = model::skipEmail) }
    }
}

// signup-create

@Composable
private fun SignupCreateScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val phase by model.createPhase.collectAsState()
    val error by model.createError.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(BipeScreen.SIGNUP_CREATE.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        if (phase == SignupFlowModel.CreatePhase.FAILED) {
            Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(48.dp))
            // error is read so the text follows it.
            Text(if (error == SignupFlowModel.CreateError.None) "" else model.createErrorText(), color = colors.inkPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            if (model.canFix) {
                PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::fix)
            } else {
                PrimaryButton(stringResource(R.string.bipe_app_retry), onClick = model::retryCreate)
            }
        } else {
            CircularProgressIndicator(color = colors.accentFill)
            Text(stringResource(R.string.bipe_app_signup_creating), color = colors.inkSecondary)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}
