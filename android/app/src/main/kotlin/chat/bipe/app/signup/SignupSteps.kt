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

// signup-invite-code

@Composable
internal fun SignupInviteCodeScreen(model: SignupFlowModel) {
    val code by model.code.collectAsState()
    val phase by model.inviteCodePhase.collectAsState()
    val error by model.inviteCodeError.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        InviteScanScreen(onText = { scanning = false; model.scannedCode(it) }, onCancel = { scanning = false })
        return
    }
    val step = BipeOnboardingStep.INVITE_CODE
    val checking = phase == SignupFlowModel.InviteCodePhase.CHECKING
    OnboardingStepLayout(
        BipeScreen.SIGNUP_INVITE_CODE,
        PhosphorIcon.HANDSHAKE,
        stringResource(step.title),
        message = step.body?.let { stringResource(it) },
        actions = {
            OnboardingPrimaryButton(stringResource(step.primary.title), enabled = code.isNotBlank(), busy = checking, onClick = model::submitCode)
            step.text.forEach { OnboardingTextButton(stringResource(it.title), onClick = model::noInvite) }
        },
    ) {
        BipeField(
            stringResource(R.string.bipe_signup_invite_code), code, model::editCode,
            isCode = true, icon = PhosphorIcon.KEY, isError = error != null, format = BipeInviteLink::typedCode,
        )
        when {
            checking -> OnboardingFieldFooter(stringResource(R.string.bipe_app_invite_checking))
            error != null -> OnboardingFieldFooter(error.orEmpty(), isError = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Action pasteCode: reads the clipboard only on this tap and continues at once.
            OnboardingSecondaryButton(
                stringResource(R.string.bipe_onboarding_paste_invite), Modifier.weight(1f),
                icon = PhosphorIcon.COPY, enabled = !checking, onClick = model::pasteCode,
            )
            // Action scanCode: the QR code on the inviter's phone holds the invite link.
            OnboardingSecondaryButton(
                stringResource(R.string.bipe_onboarding_scan_invite), Modifier.weight(1f),
                icon = PhosphorIcon.QR_CODE, enabled = !checking,
            ) { scanning = true }
        }
    }
}

/**
 * Action scanCode of signup-invite-code: the camera over the whole step. The first QR code read closes it and hands
 * over its text; scannedCode takes the code out of it. No Dialog: QrScanner binds the camera to the activity.
 */
@Composable
private fun InviteScanScreen(onText: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    var cameraAllowed by remember { mutableStateOf<Boolean?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraAllowed = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    BackHandler(onBack = onCancel)
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.Start)) { Text(stringResource(R.string.bipe_app_cancel)) }
            Text(stringResource(R.string.bipe_app_invite_scan_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
            when (cameraAllowed) {
                null -> CircularProgressIndicator(color = colors.accentFill)
                false -> {
                    Text(stringResource(R.string.bipe_camera_permission_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    BipeButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
                }
                true -> {
                    Text(stringResource(R.string.bipe_app_invite_scan_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    QrScanner(
                        onCode = { bytes -> onText(bytes.decodeToString()) },
                        readText = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
        }
    }
}

// signup-waitlist

@Composable
internal fun SignupWaitlistScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val email by model.waitlistEmail.collectAsState()
    val phase by model.waitlistPhase.collectAsState()
    val invalid by model.waitlistEmailInvalid.collectAsState()
    val error by model.waitlistError.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.surfaceBase)
            .background(Brush.verticalGradient(listOf(colors.accentWash, colors.surfaceBase)))
            .padding(24.dp)
            .testTag(BipeScreen.SIGNUP_WAITLIST.id),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        OnboardingStepHeader(PhosphorIcon.BELL)
        Text(stringResource(BipeOnboarding.waitlistBody), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        if (phase == SignupFlowModel.WaitlistPhase.DONE) {
            Row(
                Modifier.fillMaxWidth().background(colors.accentWash, RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess, modifier = Modifier.size(24.dp))
                Text(stringResource(BipeOnboarding.waitlistDone), style = MaterialTheme.typography.titleMedium, color = colors.statusSuccess)
            }
        } else {
            BipeField(stringResource(R.string.bipe_signup_email), email, model::editWaitlistEmail, KeyboardType.Email, icon = PhosphorIcon.BELL, isError = invalid || error != null)
            when {
                invalid -> OnboardingFieldFooter(stringResource(R.string.bipe_signup_email_invalid), isError = true)
                error != null -> OnboardingFieldFooter(error.orEmpty(), isError = true)
            }
            OnboardingPrimaryButton(stringResource(BipeOnboarding.waitlistPrimary.title), busy = phase == SignupFlowModel.WaitlistPhase.SENDING, onClick = model::joinWaitlist)
        }
        OnboardingTextButton(stringResource(R.string.bipe_app_close), onClick = model::closeWaitlist)
    }
}

// signup-name

@Composable
internal fun SignupNameScreen(model: SignupFlowModel) {
    val colors = BipeTheme.colors
    val name by model.displayName.collectAsState()
    val accepted by model.termsAccepted.collectAsState()
    val required by model.showTermsRequired.collectAsState()
    val invite by model.invite.collectAsState()
    val step = BipeOnboardingStep.NAME
    val glyph = if (model.isCompany) PhosphorIcon.BRIEFCASE else PhosphorIcon.USER_CIRCLE
    OnboardingStepLayout(
        BipeScreen.SIGNUP_NAME,
        glyph,
        stringResource(step.title),
        step = 1,
        actions = {
            OnboardingPrimaryButton(stringResource(step.primary.title), enabled = model.canContinueName(name, accepted), onClick = model::continueFromName)
        },
    ) {
        val handle = invite?.takeIf { it.isValid }?.inviterHandle
        if (handle != null) {
            Row(
                Modifier.background(colors.accentWash, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(PhosphorIcon.HANDSHAKE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.bipe_signup_invited_by, handle), style = MaterialTheme.typography.titleSmall, color = colors.accentInk)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BipeField(stringResource(if (model.isCompany) R.string.bipe_company_name else R.string.bipe_signup_display_name), name, model::editName, icon = glyph)
            OnboardingFieldFooter(stringResource(R.string.bipe_signup_display_name_footer))
        }
        // The person's region, read-only (ADR 0197).
        OnboardingNote(PhosphorIcon.LOCK_SIMPLE, stringResource(R.string.bipe_onboarding_point_brazil))
        OnboardingCard {
            TermsRow(accepted, onToggle = model::toggleTerms)
            if (required) OnboardingFieldFooter(stringResource(R.string.bipe_signup_terms_required), isError = true)
        }
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { stateDescription = if (accepted) "✓" else "" },
    ) {
        OnboardingCheckbox(accepted)
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary))
    }
}
