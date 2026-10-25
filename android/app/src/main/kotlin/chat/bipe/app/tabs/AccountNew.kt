// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.recovery.RecoveryFlowModel
import chat.bipe.app.recovery.RecoveryFlowScreen
import chat.bipe.app.signup.SignupFlowModel
import chat.bipe.app.signup.SignupFlowScreen
import chat.bipe.app.signup.SignupStart
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.createInvite
import chat.bipe.shared.api.myInvites
import chat.bipe.shared.api.revokeInvite
import chat.bipe.shared.generated.BipeScreen
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec account-new (bipe-chat/bipe#445): the invite quota of the account on screen, and the invite a tap makes. */
class AccountNewModel(private val context: Context, private val session: AccountSession, private val api: BipeApiClient) {
    enum class InvitePhase { LOADING, READY, NONE_LEFT, FAILED }

    data class State(
        val invitePhase: InvitePhase = InvitePhase.LOADING,
        val quotaText: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear: reads the quota. Creates nothing. */
    suspend fun appear() {
        try {
            val quota = withToken { api.myInvites(it) }
            if (quota.available > 0) _state.value = _state.value.copy(invitePhase = InvitePhase.READY)
            else noneLeft(quota.nextAvailableAt)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { _state.value = _state.value.copy(invitePhase = InvitePhase.FAILED) }
    }

    /** Action choosePersonal: the invite for the sign-up, else `null` with the reason on screen. */
    suspend fun choosePersonal(): SignupStart? {
        if (_state.value.busy) return null
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val quota = withToken { api.myInvites(it) }
            if (quota.available <= 0) {
                noneLeft(quota.nextAvailableAt)
                return null
            }
            val invite = withToken { api.createInvite(it) }
            return SignupStart(invite.id, invite.code)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error is BipeApiException.Server && error.code == BipeApiErrorCode.INVITE_QUOTA_EXHAUSTED) noneLeft(null)
            else _state.value = _state.value.copy(
                error = (error as? BipeApiException)?.localizedMessage(context) ?: context.getString(R.string.bipe_error_network),
            )
            return null
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    /** Action cancelSignup: gives the slot back. A failed revoke leaves a pending invite in Convites, nothing worse. */
    suspend fun revoke(start: SignupStart) {
        try { withToken { api.revokeInvite(start.inviteId, it) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { }
    }

    private fun noneLeft(nextAvailableAt: String?) {
        val none = context.getString(R.string.bipe_invites_none_left)
        val next = nextAvailableAt?.let { raw ->
            val label = runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(raw))) }
                .getOrDefault(raw)
            context.getString(R.string.bipe_invites_next, label)
        }
        _state.value = _state.value.copy(invitePhase = InvitePhase.NONE_LEFT, quotaText = listOfNotNull(none, next).joinToString(" "))
    }

    private suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)
}

/**
 * The Nova conta screen: the choices, then the sign-up or recovery-start in its place. Conta da empresa stays hidden on
 * Android until the phone can attest a company sign-up (bipe-chat/bipe#92).
 */
@Composable
fun AccountNewScreen(tabs: AppTabsModel, session: AccountSession, api: BipeApiClient, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { AccountNewModel(context, session, api) }
    val state by model.state.collectAsState()
    val tabsState by tabs.state.collectAsState()
    var signup by remember { mutableStateOf<SignupStart?>(null) }
    var showsExisting by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.appear() }

    val start = signup
    if (start != null) {
        val flow = remember(start) {
            SignupFlowModel(context, api, tabs.start.vault, tabs.start.sessionStore, scope, onSignedUp = { created, outcome ->
                onClose()
                tabs.start.didSignUp(created, outcome)
            }, start = start)
        }
        // Action cancelSignup: before signup-create the invite goes back to the account on screen.
        val cancel: (Boolean) -> Unit = { reachedCreate ->
            onClose()
            if (!reachedCreate) scope.launch { model.revoke(start) }
        }
        BackHandler { cancel(flow.reachedCreate) }
        SignupFlowScreen(flow, onCancel = cancel)
        return
    }
    if (showsExisting) {
        val recovery = remember {
            RecoveryFlowModel(context, api, tabs.start.vault, tabs.start.recoveryTokens, tabs.start.sessionStore, scope, onSignedIn = { recovered ->
                onClose()
                tabs.start.didRecover(recovered)
            })
        }
        BackHandler { showsExisting = false }
        RecoveryFlowScreen(recovery, onClose = { showsExisting = false })
        return
    }
    BackHandler(onBack = onClose)
    val current = tabsState.accounts.firstOrNull { it.isCurrent }
    val invitesLeft = state.invitePhase != AccountNewModel.InvitePhase.NONE_LEFT
    PeopleScaffold(stringResource(R.string.bipe_account_new_title), BipeScreen.ACCOUNT_NEW.id, onBack = onClose) {
        ChoiceRow(
            PhosphorIcon.USER_CIRCLE,
            stringResource(R.string.bipe_account_new_personal),
            if (tabsState.canAddAccount) stringResource(R.string.bipe_account_new_personal_body)
            else stringResource(R.string.bipe_account_cap_reached, tabsState.maxPersonalAccounts.toString()),
            enabled = tabsState.canAddAccount && invitesLeft && !state.busy,
        ) {
            scope.launch { model.choosePersonal()?.let { signup = it } }
        }
        val note = state.quotaText?.takeIf { !invitesLeft }
            ?: current?.displayName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.bipe_account_new_invite_note, it) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary) }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.trustDangerInk) }
        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), color = BipeTheme.colors.accentFill)
        HorizontalDivider()
        ChoiceRow(
            PhosphorIcon.KEY,
            stringResource(R.string.bipe_account_new_existing),
            stringResource(R.string.bipe_account_new_existing_body),
            enabled = !state.busy,
        ) {
            showsExisting = true
        }
    }
}

@Composable
private fun ChoiceRow(icon: PhosphorIcon, title: String, body: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = if (enabled) colors.accentInk else colors.inkDisabled,
            modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.inkPrimary else colors.inkDisabled)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
    }
}
