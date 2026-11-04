// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SignupOutcome
import chat.bipe.app.common.BipeMarkShape
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

/** Spec signup-done (bipe-shared product/screens/signup-done.json). */
class SignupDoneModel(
    private val session: AccountSession,
    outcome: SignupOutcome,
    private val scope: CoroutineScope,
    /** The room to open: a chat ID, or `null` for the chat list. */
    private val onContinue: (String?) -> Unit,
) {
    enum class Phase { SHOWN, OPENING_CHAT }

    private val _phase = MutableStateFlow(Phase.SHOWN)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val inviterUserId = outcome.inviterUserId
    val inviterHandle = outcome.inviterHandle
    private val groupRoomId = outcome.groupRoomId
    private var continued = false

    /** Action appear: the success line for a short moment, then continue. */
    fun appear() {
        scope.launch {
            delay(1_500)
            continueFlow()
        }
    }

    /** Action continue: the chat with the inviter, or the chat list. */
    fun continueFlow() {
        if (continued) return
        continued = true
        val group = groupRoomId
        if (group != null) {
            // Spec group-link-open: the groups service account invited the new account at sign-up. The group opens first.
            _phase.value = Phase.OPENING_CHAT
            scope.launch {
                if (runCatching { session.client.joinRoomById(group) }.isSuccess) return@launch onContinue(group)
                val inviter = inviterUserId ?: return@launch onContinue(null)
                onContinue(directChat(inviter))
            }
            return
        }
        val inviter = inviterUserId ?: return onContinue(null)
        _phase.value = Phase.OPENING_CHAT
        scope.launch { onContinue(directChat(inviter)) }
    }

    /** The DM with the inviter: the existing one, else a new encrypted one that invites them. Never a second DM. */
    private suspend fun directChat(userId: String): String? {
        val client = session.client
        runCatching { client.getDmRoom(userId) }.getOrNull()?.let { return it.id() }
        val parameters = CreateRoomParameters(
            name = null,
            isEncrypted = true,
            isDirect = true,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
            invite = listOf(userId),
        )
        val roomId = runCatching { client.createRoom(parameters) }.getOrNull() ?: return null
        runCatching { client.awaitRoomRemoteEcho(roomId) }
        return roomId
    }
}

@Composable
fun SignupDoneScreen(session: AccountSession, outcome: SignupOutcome, onContinue: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(session) { SignupDoneModel(session, outcome, scope, onContinue) }
    val phase by model.phase.collectAsState()
    val colors = BipeTheme.colors
    LaunchedEffect(model) { model.appear() }
    Surface(
        modifier = Modifier.fillMaxSize().clickable(onClick = model::continueFlow).testTag(BipeScreen.SIGNUP_DONE.id),
        color = colors.surfaceBase,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(56.dp).background(colors.accentFill, BipeMarkShape))
            Text(
                stringResource(BipeOnboarding.created),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            if (phase == SignupDoneModel.Phase.OPENING_CHAT) CircularProgressIndicator(color = colors.accentFill)
        }
    }
}
