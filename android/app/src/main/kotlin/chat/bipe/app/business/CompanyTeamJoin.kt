// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SessionStore
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.recovery.OldPhoneContent
import chat.bipe.app.recovery.OldPhoneModel
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeKeyAttestation
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.claimSeat
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface JoinPhase {
    data object Scan : JoinPhase
    data object Joining : JoinPhase
    data class Welcome(val session: AccountSession, val name: String) : JoinPhase
    data class Failed(val message: Int) : JoinPhase
}

/**
 * Spec company-team-join and company-team-welcome: this phone scans the Responsável's code, claims the seat with an
 * attestation, and says what customers see and what the people on the account share. It stores no hidden password,
 * recovery token or PIN. A failed claim signs the account out of this phone.
 */
@Composable
fun CompanyTeamJoinScreen(api: BipeApiClient, sessionStore: SessionStore, onClose: () -> Unit, onDone: (AccountSession) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<JoinPhase>(JoinPhase.Scan) }
    val refuse: suspend (AccountSession, Int) -> Unit = { session, message ->
        session.signOut(context.applicationContext, sessionStore, BipeAccountVault(context.applicationContext))
        phase = JoinPhase.Failed(message)
    }
    val model = remember {
        OldPhoneModel(context.applicationContext, sessionStore, scope, onSignedIn = { session ->
            phase = JoinPhase.Joining
            scope.launch {
                try {
                    // Action claim: a new challenge, then the claim.
                    val attestation = BipeKeyAttestation.attestation(context.applicationContext, api)
                    if (attestation == null) {
                        refuse(session, R.string.bipe_team_join_attest_failed)
                        return@launch
                    }
                    val list = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.claimSeat(attestation, it) }
                    CompanyRole.remember(context.applicationContext, session.userId, list.me.role)
                    val name = runCatching { session.client.displayName() }.getOrNull().orEmpty()
                    phase = JoinPhase.Welcome(session, name)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    refuse(session, when ((error as? BipeApiException.Server)?.code) {
                        BipeApiErrorCode.ATTESTATION_INVALID -> R.string.bipe_team_join_attest_failed
                        BipeApiErrorCode.SEAT_WINDOW_CLOSED, BipeApiErrorCode.NOT_FOUND -> R.string.bipe_team_add_expired
                        else -> R.string.bipe_team_error
                    })
                }
            }
        })
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.COMPANY_TEAM_JOIN.id), horizontalAlignment = Alignment.CenterHorizontally) {
        when (val current = phase) {
            JoinPhase.Scan -> {
                OldPhoneContent(model, joining = true)
                BipeOutlinedButton(onClick = onClose, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.bipe_app_cancel)) }
            }
            JoinPhase.Joining -> CircularProgressIndicator(Modifier.padding(32.dp), color = BipeTheme.colors.accentFill)
            is JoinPhase.Welcome -> Welcome(current.name) { onDone(current.session) }
            is JoinPhase.Failed -> {
                Text(stringResource(current.message), color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(24.dp))
                BipeOutlinedButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
            }
        }
    }
}

@Composable
private fun Welcome(name: String, onOk: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag(BipeScreen.COMPANY_TEAM_WELCOME.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.bipe_team_welcome_title, name), style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_team_welcome_body, name), color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_team_shared_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_team_shared_chats), color = colors.inkSecondary)
        Text(stringResource(R.string.bipe_team_shared_read), color = colors.inkSecondary)
        Text(stringResource(R.string.bipe_team_shared_changes), color = colors.inkSecondary)
        BipeButton(onClick = onOk, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_team_welcome_ok)) }
    }
}
