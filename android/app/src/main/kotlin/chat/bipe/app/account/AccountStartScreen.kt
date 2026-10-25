// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.BipeApplication
import chat.bipe.app.chatlist.HomeScreen
import chat.bipe.app.recovery.RecoveryFlowModel
import chat.bipe.app.recovery.RecoveryFlowScreen
import chat.bipe.app.signup.SignupDoneScreen
import chat.bipe.app.signup.SignupFlowModel
import chat.bipe.app.signup.SignupFlowScreen
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.BipeNotificationDestination
import chat.bipe.shared.generated.BipeScreen

/** Spec account-start: no login screen (ADR 0057). Shows app-tabs when the current account is ready. */
@Composable
fun AccountStartScreen(model: AccountStartModel, tabs: AppTabsModel) {
    val app = LocalContext.current.applicationContext as BipeApplication
    val notificationTarget by app.roomToOpen.collectAsState()
    val phase by model.phase.collectAsState()
    val outcome by model.signupOutcome.collectAsState()
    val foregroundCount by model.foregroundCount.collectAsState()
    val userId by model.userId.collectAsState()
    val sessions by model.sessions.collectAsState()
    LaunchedEffect(notificationTarget, sessions) {
        val target = notificationTarget ?: return@LaunchedEffect
        if (sessions.any { it.userId == target.accountUserId }) {
            model.switchAccount(target.accountUserId)
            tabs.selectTab(if (target.destination == BipeNotificationDestination.GROUPS) AppTabsModel.Tab.GRUPOS else AppTabsModel.Tab.CONVERSAS)
        }
    }
    // Spec soft-logout removed: once, as a toast.
    val notice by model.notice.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            model.noticeShown()
        }
    }
    // Spec soft-logout needsPin: recovery-pin for that account, "Digite sua senha para continuar".
    val needsPin by model.needsPin.collectAsState()
    val pinFor = needsPin
    if (phase == AccountStartModel.Phase.READY && pinFor != null) {
        val scope = rememberCoroutineScope()
        val recovery = remember(pinFor) {
            RecoveryFlowModel(context, model.api, model.vault, model.recoveryTokens, model.sessionStore, scope,
                onSignedIn = model::didEnterPin, softLogoutUserId = pinFor)
        }
        RecoveryFlowScreen(recovery, onClose = model::dismissPin)
        return
    }
    val session = sessions.firstOrNull { it.userId == userId }
    if (phase == AccountStartModel.Phase.READY && session != null) {
        val signedUp = outcome
        if (signedUp != null) {
            SignupDoneScreen(session, signedUp, onContinue = model::finishSignup)
        } else {
            // One screen per account: a switch builds the tabs again for the new account.
            // A quiet sign-in after soft-logout replaces the session: build the tabs again too.
            key(session) {
                HomeScreen(session, tabs, model.api, model.initialRoomId, foregroundCount, onSignOut = model::signOut)
            }
        }
        return
    }
    val showsRecovery by model.showsRecovery.collectAsState()
    if (phase == AccountStartModel.Phase.NO_ACCOUNT && showsRecovery) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val recovery = remember(model) {
            RecoveryFlowModel(context, model.api, model.vault, model.recoveryTokens, model.sessionStore, scope, onSignedIn = model::didRecover)
        }
        val link by model.recoveryLink.collectAsState()
        LaunchedEffect(link) {
            link?.let {
                recovery.openLink(it)
                model.consumeRecoveryLink()
            }
        }
        RecoveryFlowScreen(recovery, onClose = { model.showRecovery(false) })
        return
    }
    if (phase == AccountStartModel.Phase.NO_ACCOUNT) {
        // Spec account-start: noAccount shows onboarding-welcome.
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val signup = remember(model) {
            SignupFlowModel(
                context,
                model.api,
                model.vault,
                model.sessionStore,
                scope,
                onSignedUp = model::didSignUp,
                onHaveAccount = { model.showRecovery(true) },
            )
        }
        LaunchedEffect(signup) { model.pendingInviteLink?.let(signup::openInviteLink) }
        SignupFlowScreen(signup)
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
