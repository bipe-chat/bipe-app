// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.signup.SignupStart
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.handle
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec invite-signed-in (bipe-chat/bipe#460). */
class InviteSignedInModel(
    val code: String,
    private val session: AccountSession,
    private val api: BipeApiClient,
    private val accountsOnPhone: List<String>,
) {
    enum class Phase { CHECKING, INVITED, NOT_VALID, FAILED }

    data class State(
        val phase: Phase = Phase.CHECKING,
        val inviterHandle: String? = null,
        val inviterName: String? = null,
        /** false when the inviter is an account on this phone: no chat row. */
        val canChat: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear. Returns the inviter's chat when one exists: the screen closes and the chat opens. */
    suspend fun appear(): String? {
        _state.value = State()
        val preview = try {
            api.invitePreview(code)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.value = State(phase = Phase.FAILED)
            return null
        }
        if (!preview.valid) {
            _state.value = State(phase = Phase.NOT_VALID)
            return null
        }
        val handle = preview.inviterHandle ?: run {
            _state.value = State(phase = Phase.INVITED)
            return null
        }
        var name = "@$handle"
        val userId = runCatching { BipeApiClient.withTokenRetry({ session.accessToken() }) { api.handle(handle, it) }.userId }.getOrNull()
        var canChat = true
        if (userId != null) {
            runCatching { session.client.getProfile(userId).displayName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name = it }
            if (userId !in accountsOnPhone) {
                runCatching { session.client.getDmRoom(userId) }.getOrNull()?.let { return it.id() }
            }
            canChat = userId !in accountsOnPhone
        }
        _state.value = State(Phase.INVITED, handle, name, canChat)
        return null
    }
}

@Composable
fun InviteSignedInScreen(
    code: String,
    session: AccountSession,
    api: BipeApiClient,
    accountsOnPhone: List<String>,
    onClose: () -> Unit,
    openChat: (String) -> Unit,
    openProfile: (String) -> Unit,
    newAccount: (SignupStart) -> Unit,
) {
    val model = remember(code) { InviteSignedInModel(code, session, api, accountsOnPhone) }
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.appear()?.let(openChat) }
    BackHandler(onBack = onClose)
    PeopleScaffold(stringResource(R.string.bipe_account_add), BipeScreen.INVITE_SIGNED_IN.id, onBack = onClose) {
        when (state.phase) {
            InviteSignedInModel.Phase.CHECKING -> CircularProgressIndicator(color = BipeTheme.colors.accentFill)
            InviteSignedInModel.Phase.INVITED -> {
                val title = state.inviterName?.let { stringResource(R.string.bipe_invite_signed_in_title, it) }
                    ?: stringResource(R.string.bipe_invite_signed_in_title_anonymous)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = BipeTheme.colors.inkPrimary)
                Text(stringResource(R.string.bipe_invite_signed_in_body), color = BipeTheme.colors.inkSecondary)
                val handle = state.inviterHandle
                if (state.canChat && handle != null) {
                    BipeButton(onClick = { openProfile(handle) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_invite_signed_in_chat))
                    }
                }
                BipeOutlinedButton(
                    onClick = { newAccount(SignupStart(inviteId = null, code = model.code, inviterHandle = state.inviterHandle)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bipe_account_add))
                }
            }
            InviteSignedInModel.Phase.NOT_VALID -> Text(stringResource(R.string.bipe_invite_no_longer_valid), color = BipeTheme.colors.inkPrimary)
            InviteSignedInModel.Phase.FAILED -> {
                Text(stringResource(R.string.bipe_error_network), color = BipeTheme.colors.inkPrimary)
                BipeButton(onClick = { scope.launch { model.appear()?.let(openChat) } }) { Text(stringResource(R.string.bipe_app_retry)) }
            }
        }
    }
}
