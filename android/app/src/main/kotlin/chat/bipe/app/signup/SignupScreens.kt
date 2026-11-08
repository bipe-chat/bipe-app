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

/** The sign-up flow from onboarding-welcome to signup-create. account-start shows it when its phase is noAccount. */
@Composable
fun SignupFlowScreen(model: SignupFlowModel, onCancel: ((reachedCreate: Boolean) -> Unit)? = null) {
    val path by model.path.collectAsState()
    val showsWaitlist by model.showsWaitlist.collectAsState()
    BackHandler(enabled = path.isNotEmpty() && model.canGoBack) { model.back() }
    // pin_set: the confirm step matched and the flow moved on.
    val view = LocalView.current
    var lastRoute by remember { mutableStateOf(path.lastOrNull()) }
    LaunchedEffect(path) {
        val route = path.lastOrNull()
        if (lastRoute == SignupFlowModel.Route.PIN_CONFIRM && (route == SignupFlowModel.Route.FALLBACK || route == SignupFlowModel.Route.CREATE)) {
            view.bipeHaptic(BipeHaptic.Success)
        }
        lastRoute = route
    }
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
                // Every step draws over the açaí backdrop, which runs under the system bars.
                else -> Box(Modifier.fillMaxSize()) {
                    OnboardingBackdrop()
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
    }
    if (showsWaitlist) {
        Dialog(onDismissRequest = model::closeWaitlist) { SignupWaitlistScreen(model) }
    }
}

// Shared pieces. The buttons, header, backdrop and footers are in OnboardingKit.kt.

/** A framed field: raised surface, a thin line, an açaí ring while it has focus, an optional glyph. */
@Composable
private fun BipeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    isCode: Boolean = false,
    icon: PhosphorIcon? = null,
    isError: Boolean = false,
    format: (String) -> String = { it },
) {
    val colors = BipeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // When format rewrites the text (the invite code adds dashes), or the model sets it, the cursor goes to the end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) { if (field.text != value) field = TextFieldValue(value, TextRange(value.length)) }
    val textStyle = if (isCode) {
        MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    BasicTextField(
        value = field,
        onValueChange = {
            val text = format(it.text)
            field = if (text == it.text) it else TextFieldValue(text, TextRange(text.length))
            onChange(text)
        },
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(
            capitalization = if (isCode) KeyboardCapitalization.Characters else KeyboardCapitalization.Unspecified,
            autoCorrectEnabled = if (isCode) false else null,
            keyboardType = keyboardType,
        ),
        textStyle = textStyle.copy(color = colors.inkPrimary),
        cursorBrush = SolidColor(colors.accentFill),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OnboardingMetrics.buttonHeight)
            .background(colors.surfaceRaised, OnboardingMetrics.fieldShape)
            .border(
                if (focused || isError) 2.dp else 1.dp,
                when {
                    isError -> colors.trustDangerLine
                    focused -> colors.accentFill
                    else -> colors.lineSubtle
                },
                OnboardingMetrics.fieldShape,
            )
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(Modifier.heightIn(min = OnboardingMetrics.buttonHeight), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(painterResource(icon.drawable), contentDescription = null, tint = if (focused) colors.accentInk else colors.glyphSecondary, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(label, style = textStyle, color = colors.inkSecondary)
                    inner()
                }
            }
        },
    )
}

// onboarding-welcome

@Composable
private fun OnboardingWelcomeScreen(model: SignupFlowModel, onCancel: (() -> Unit)?) {
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

// signup-invite-code

@Composable
private fun SignupInviteCodeScreen(model: SignupFlowModel) {
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
private fun SignupWaitlistScreen(model: SignupFlowModel) {
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
private fun SignupNameScreen(model: SignupFlowModel) {
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

// signup-pin

@Composable
private fun SignupPinScreen(model: SignupFlowModel, confirm: Boolean) {
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
private fun SignupFallbackScreen(model: SignupFlowModel) {
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
private fun SignupCreateScreen(model: SignupFlowModel) {
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
