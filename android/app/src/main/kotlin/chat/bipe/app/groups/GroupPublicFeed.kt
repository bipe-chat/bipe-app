// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeMyFeed
import chat.bipe.shared.api.myFeed
import chat.bipe.shared.api.setMyFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec group-privacy publicFeedHandle and setPublicFeed (bipe ADR 0280 point 7c, bipe-chat/bipe#1260): the handle owner
 * turns on or off the public feed that staff linked to this group. Nobody else sees the row.
 */
class GroupPublicFeedModel(
    private val session: AccountSession,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val api: BipeApiClient = BipeApiClient(AppConfig.API_BASE_URL),
) {
    sealed interface Phase {
        data object Hidden : Phase
        data class Shown(val handle: String, val enabled: Boolean, val working: Boolean = false) : Phase
    }

    data class State(val phase: Phase = Phase.Hidden, val failed: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Reads GET /v1/me/feed. Any failure, or a feed of another group, hides the row. */
    fun appear() {
        scope.launch {
            val feed = runCatching { withToken { api.myFeed(it) } }.getOrNull()
            _state.update { it.copy(phase = shown(feed)) }
        }
    }

    /** Action setPublicFeed. A second tap while it runs does nothing; a failure keeps the old value. */
    fun set(enabled: Boolean) {
        val current = _state.value.phase as? Phase.Shown ?: return
        if (current.working || current.enabled == enabled) return
        _state.update { it.copy(phase = current.copy(enabled = enabled, working = true)) }
        scope.launch {
            runCatching { withToken { api.setMyFeed(enabled, it) } }
                .onSuccess { feed -> _state.update { it.copy(phase = shown(feed)) } }
                .onFailure { _state.update { it.copy(phase = current, failed = true) } }
        }
    }

    fun dismissError() {
        _state.update { it.copy(failed = false) }
    }

    private fun shown(feed: BipeMyFeed?): Phase =
        if (feed != null && feed.roomId == roomId) Phase.Shown(feed.handle, feed.enabled) else Phase.Hidden

    private suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)
}

/** The rows of group-privacy for the public feed. Nothing while the row is hidden. */
@Composable
fun GroupPublicFeedRows(phase: GroupPublicFeedModel.Phase, onChange: (Boolean) -> Unit) {
    val shown = phase as? GroupPublicFeedModel.Phase.Shown ?: return
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = shown.enabled, enabled = !shown.working, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.bipe_group_public_feed_title, shown.handle),
            color = if (shown.working) colors.inkDisabled else colors.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = shown.enabled, onCheckedChange = null, enabled = !shown.working)
    }
    GroupFooter(stringResource(R.string.bipe_group_public_feed_footer, shown.handle))
}
