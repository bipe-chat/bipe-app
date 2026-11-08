// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.bipeHaptic
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic

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
internal fun BipeField(
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
