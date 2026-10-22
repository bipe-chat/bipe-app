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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.window.Dialog
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.AppConfig
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeLegalLinks
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingStart
import chat.bipe.shared.generated.BipeOnboardingStep
import chat.bipe.shared.generated.BipeOnboardingTarget
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen

/**
 * The sign-up flow from onboarding-welcome to signup-create. account-start shows it when its phase is noAccount.
 * [onHaveAccount] opens recovery-start ("Já tenho conta").
 */
@Composable
fun SignupFlowScreen(model: SignupFlowModel, onHaveAccount: () -> Unit) {
    val path by model.path.collectAsState()
    val showsWaitlist by model.showsWaitlist.collectAsState()
    BackHandler(enabled = path.isNotEmpty()) { model.back() }
    Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            when (path.lastOrNull()) {
                null -> OnboardingWelcomeScreen(model, onHaveAccount)
                SignupFlowModel.Route.INVITE_CODE -> SignupInviteCodeScreen(model)
                SignupFlowModel.Route.NAME -> SignupNameScreen(model)
                SignupFlowModel.Route.PIN -> SignupPinScreen(model, confirm = false)
                SignupFlowModel.Route.PIN_CONFIRM -> SignupPinScreen(model, confirm = true)
                SignupFlowModel.Route.FALLBACK -> SignupFallbackScreen(model)
                SignupFlowModel.Route.CREATE -> SignupCreateScreen(model)
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
internal fun StepLayout(screen: BipeScreen, title: String, message: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
internal fun PrimaryButton(title: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
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
internal fun TextButtonRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = if (enabled) BipeTheme.colors.accentInk else BipeTheme.colors.inkDisabled,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
internal fun FieldFooter(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) BipeTheme.colors.trustDangerInk else BipeTheme.colors.inkSecondary)
}

@Composable
internal fun BipeField(
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
private fun OnboardingWelcomeScreen(model: SignupFlowModel, onHaveAccount: () -> Unit) {
    val colors = BipeTheme.colors
    val variant by model.variant.collectAsState()
    val invite by model.invite.collectAsState()
    val clipboardHasText by model.clipboardHasText.collectAsState()
    val pasteResult by model.pasteResult.collectAsState()
    LaunchedEffect(Unit) { model.appear() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(BipeScreen.ONBOARDING_WELCOME.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(painterResource(PhosphorIcon.CHAT_CIRCLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.bipe_onboarding_welcome), style = MaterialTheme.typography.titleMedium, color = colors.accentInk)
        val start = model.startVariant
        val handle = invite?.inviterHandle
        val title = if (start == BipeOnboardingStart.INVITED && handle != null) {
            stringResource(start.title, "@$handle")
        } else {
            stringResource(start.title)
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        // The order of linguagem.md section 3: scam check, Brazil, no ads.
        listOf(R.string.bipe_onboarding_point_scam, R.string.bipe_onboarding_point_brazil, R.string.bipe_onboarding_point_no_ads).forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).background(colors.accentFill, CircleShape))
                Text(stringResource(it), color = colors.inkPrimary)
            }
        }
        Spacer(Modifier.weight(1f))
        if (variant == SignupFlowModel.Variant.LOADING) {
            CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            PrimaryButton(stringResource(start.primary.title), onClick = model::primary)
            if (variant == SignupFlowModel.Variant.INVITE_ONLY && clipboardHasText) {
                Column(Modifier.clickable(role = Role.Button, onClick = model::pasteInvite)) {
                    Text(stringResource(R.string.bipe_onboarding_paste_invite), style = MaterialTheme.typography.titleSmall, color = colors.accentInk)
                    Text(stringResource(R.string.bipe_onboarding_paste_invite_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
                if (pasteResult == SignupFlowModel.PasteResult.NOT_AN_INVITE) {
                    FieldFooter(stringResource(R.string.bipe_onboarding_paste_invite_none), isError = true)
                }
            }
            start.text.forEach { button ->
                TextButtonRow(stringResource(button.title)) {
                    // Action haveAccount: recovery-start.
                    if (button.target == BipeOnboardingTarget.RECOVERY) onHaveAccount()
                }
            }
        }
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
        BipeField(stringResource(R.string.bipe_signup_display_name), name, model::editName)
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
        // The system digit keyboard (spec: the fallback of the number pad). It brings delete and paste.
        BasicTextField(
            value = pin,
            onValueChange = model::editPin,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.surfaceBase),
            cursorBrush = SolidColor(colors.surfaceBase),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
    val step = BipeOnboardingStep.FALLBACK
    StepLayout(BipeScreen.SIGNUP_FALLBACK, stringResource(step.title), step.body?.let { stringResource(it) }) {
        if (phase == SignupFlowModel.FallbackPhase.CHOOSE) {
            PrimaryButton(stringResource(step.primary.title), onClick = model::addEmail)
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
