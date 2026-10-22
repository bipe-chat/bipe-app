// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.HomeScreen
import chat.bipe.app.recovery.RecoveryFlowModel
import chat.bipe.app.recovery.RecoveryFlowScreen
import chat.bipe.app.recovery.RecoveryWaitingModel
import chat.bipe.app.recovery.RecoveryWaitingScreen
import chat.bipe.app.signup.SignupDoneScreen
import chat.bipe.app.signup.SignupFlowModel
import chat.bipe.app.signup.SignupFlowScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec account-start: no login screen (ADR 0057). Shows chat-list when the account is ready. */
@Composable
fun AccountStartScreen(model: AccountStartModel) {
    val phase by model.phase.collectAsState()
    val outcome by model.signupOutcome.collectAsState()
    val foregroundCount by model.foregroundCount.collectAsState()
    val session = model.session
    if (phase == AccountStartModel.Phase.READY && session != null) {
        val signedUp = outcome
        if (signedUp != null) {
            SignupDoneScreen(session, signedUp, onContinue = model::finishSignup)
        } else {
            HomeScreen(session, model.api, model.initialRoomId, foregroundCount, onSignOut = model::signOut)
        }
        return
    }
    if (phase == AccountStartModel.Phase.NO_ACCOUNT) {
        // Spec account-start: noAccount shows onboarding-welcome.
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val signup = remember(model) {
            SignupFlowModel(context, model.api, model.vault, model.sessionStore, scope, onSignedUp = model::didSignUp)
        }
        val recovery = remember(model) {
            RecoveryFlowModel(
                context,
                model.api,
                model.vault,
                model.sessionStore,
                scope,
                onSignedIn = model::didRecover,
                onWaiting = model::didStartRecovery,
            )
        }
        val hasPendingRecovery by model.hasPendingRecovery.collectAsState()
        val recoveryLink by model.pendingRecoveryLink.collectAsState()
        var showsRecovery by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(signup) { model.pendingInviteLink?.let(signup::openInviteLink) }
        LaunchedEffect(recoveryLink) {
            val link = recoveryLink ?: return@LaunchedEffect
            model.pendingRecoveryLink.value = null
            recovery.openLink(link)
            showsRecovery = true
        }
        when {
            // Spec account-start: a kept recovery opens recovery-waiting instead of noAccount.
            hasPendingRecovery -> {
                val waiting = remember(model) {
                    RecoveryWaitingModel(
                        context,
                        model.api,
                        model.vault,
                        model.sessionStore,
                        scope,
                        onSignedIn = model::didRecover,
                        onStartOver = model::startRecoveryOver,
                    )
                }
                RecoveryWaitingScreen(waiting, foregroundCount)
            }
            showsRecovery -> RecoveryFlowScreen(recovery, onClose = { showsRecovery = false })
            else -> SignupFlowScreen(signup, onHaveAccount = { showsRecovery = true })
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(BipeScreen.ACCOUNT_START.id),
        color = BipeTheme.colors.surfaceBase,
    ) {
        when (phase) {
            AccountStartModel.Phase.FAILED -> StartMessage(
                icon = PhosphorIcon.WARNING_CIRCLE,
                title = stringResource(R.string.bipe_app_name),
                message = stringResource(R.string.bipe_app_start_error),
                onRetry = model::retry,
            )
            else -> StartMessage(
                icon = PhosphorIcon.CHAT_CIRCLE,
                title = stringResource(R.string.bipe_app_name),
                message = stringResource(R.string.bipe_app_start_loading),
                showsProgress = true,
            )
        }
    }
}

@Composable
private fun StartMessage(
    icon: PhosphorIcon,
    title: String,
    message: String,
    showsProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary, textAlign = TextAlign.Center)
        if (showsProgress) {
            CircularProgressIndicator(color = colors.accentFill)
        }
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(PhosphorIcon.ARROW_CLOCKWISE.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.bipe_app_retry))
                }
            }
        }
    }
}
