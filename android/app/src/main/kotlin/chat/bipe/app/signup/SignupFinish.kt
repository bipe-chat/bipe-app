// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboardingStep
import chat.bipe.shared.generated.BipeScreen

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
