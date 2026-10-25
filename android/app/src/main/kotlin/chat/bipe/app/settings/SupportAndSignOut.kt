// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.supportRoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Membership

/** Spec support-open: opens the user's chat with Suporte Bipe (ADR 0114). */
class SupportOpenModel(private val session: AccountSession, private val api: BipeApiClient, private val scope: CoroutineScope) {
    sealed interface Phase {
        data object Opening : Phase
        data class Failed(val message: Int) : Phase
        data class Open(val roomId: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Opening)
    val phase: StateFlow<Phase> = _phase
    private var running = false

    /** Action open: POST /v1/me/support, join the room when needed, then chat-timeline. Only one open runs at a time. */
    fun open() {
        if (running) return
        running = true
        _phase.value = Phase.Opening
        scope.launch {
            _phase.value = try {
                val roomId = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.supportRoomId(it) }
                if (session.client.getRoom(roomId)?.membership() != Membership.JOINED) {
                    session.client.joinRoomById(roomId)
                }
                Phase.Open(roomId)
            } catch (error: BipeApiException) {
                Phase.Failed(
                    when {
                        error is BipeApiException.Network -> R.string.bipe_error_network
                        error is BipeApiException.Server && error.code == BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        else -> R.string.bipe_support_open_failed
                    },
                )
            } catch (error: Exception) {
                Phase.Failed(R.string.bipe_support_open_failed)
            } finally {
                running = false
            }
        }
    }
}

@Composable
fun SupportOpenScreen(session: AccountSession, api: BipeApiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(session) { SupportOpenModel(session, api, scope) }
    val media = remember(session) { MediaLoader(session.client) }
    val phase by model.phase.collectAsState()
    LaunchedEffect(model) { model.open() }
    val colors = BipeTheme.colors
    when (val current = phase) {
        is SupportOpenModel.Phase.Open -> ChatTimelineScreen(session, media, current.roomId, onBack = onBack)
        else -> Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.SUPPORT_OPEN.id), color = colors.surfaceBase) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (current is SupportOpenModel.Phase.Failed) {
                    Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
                    Text(stringResource(current.message), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    BipeButton(onClick = model::open) { Text(stringResource(R.string.bipe_app_retry)) }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.bipe_app_cancel)) }
                } else {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_app_support_opening), color = colors.inkSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Spec sign-out: says what the user needs to come back before Bipe signs this phone out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignOutSheet(model: SettingsModel, onDismiss: () -> Unit, onSignedOut: () -> Unit) {
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var signingOut by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var isLastDevice by remember { mutableStateOf(false) }
    val waysBack = state.waysBack?.ways.orEmpty()
    // Action appear. No haptic: registries/haptics.json has no sign-out use yet.
    LaunchedEffect(Unit) {
        isLastDevice = runCatching { model.session.client.encryption().isLastDevice() }.getOrDefault(false)
    }
    ModalBottomSheet(
        onDismissRequest = { if (!signingOut) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surfaceBase,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .testTag(BipeScreen.SIGN_OUT.id),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.bipe_app_sign_out_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_app_sign_out_body, BipeRegistries.PIN_LENGTH.toString()), color = colors.inkPrimary)
            // The ways back by name, one per line (linguagem.md section 6).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                waysBack.forEach { Text(stringResource(it.title), color = colors.inkPrimary) }
            }
            if (isLastDevice && !state.hasRecoveryKey) {
                Text(stringResource(R.string.bipe_app_sign_out_history), color = colors.trustCautionInk)
            }
            if (waysBack.isEmpty()) {
                Text(stringResource(R.string.bipe_app_sign_out_no_way_back), color = colors.trustDangerInk)
                // Action addWayBack: back to Sua conta, at Jeitos de voltar.
                BipeButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_sign_out_add_way))
                }
            }
            if (failed) {
                Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk)
            }
            // Action signOut: Client.logout, then the account's password, session and SDK store leave this phone.
            BipeOutlinedButton(
                onClick = {
                    signingOut = true
                    failed = false
                    scope.launch {
                        val loggedOut = runCatching { model.session.client.logout() }.isSuccess
                        signingOut = false
                        if (loggedOut) onSignedOut() else failed = true
                    }
                },
                enabled = !signingOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.trustDangerInk),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (signingOut) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.trustDangerInk)
                        Text(stringResource(R.string.bipe_app_signing_out))
                    }
                } else {
                    Text(stringResource(R.string.bipe_account_sign_out))
                }
            }
            // Action cancel.
            TextButton(onClick = onDismiss, enabled = !signingOut, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(stringResource(R.string.bipe_app_cancel))
            }
        }
    }
}
