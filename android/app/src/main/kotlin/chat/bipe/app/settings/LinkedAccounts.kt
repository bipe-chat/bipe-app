// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.recovery.LinkWebFlow
import chat.bipe.app.recovery.RecoveryAlertModel
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeLinkProvider
import chat.bipe.shared.account.link
import chat.bipe.shared.account.linkProviders
import chat.bipe.shared.account.startLinkWebFlow
import chat.bipe.shared.account.unlink
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec linked-accounts (ADR 0096): links an Apple or a Google account as a recovery factor, shows the linked ones and
 * unlinks them. Android runs the web flow in a Custom Tab for both providers.
 */
class LinkedAccountsModel(private val context: Context, private val model: SettingsModel, private val scope: CoroutineScope) {
    enum class Phase { LOADING, SHOWN, LINKING, UNLINKING, FAILED }

    data class Row(val provider: BipeLinkProvider, val linked: Boolean, val available: Boolean)

    data class State(
        val rows: List<Row> = emptyList(),
        val phase: Phase = Phase.LOADING,
        /** A string resource: bipe_linked_account_error, _taken, _unlink_error or bipe_error_network. */
        val error: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear. */
    fun appear() {
        scope.launch { load() }
    }

    private suspend fun load() {
        val providers = runCatching { model.api.linkProviders().usableOnAndroid }.getOrDefault(emptyList())
        val account = runCatching { model.authorized { model.api.account(it) } }.getOrNull()
        if (account == null) {
            _state.update { it.copy(phase = Phase.FAILED, error = R.string.bipe_error_network) }
            return
        }
        val linked = account.links.map { it.provider }.toSet()
        _state.value = State(
            rows = BipeLinkProvider.entries
                .map { Row(it, it in linked, it in providers) }
                .filter { it.linked || it.available },
            phase = Phase.SHOWN,
        )
        model.load()
    }

    /** Action link: the owner check, the web flow in a Custom Tab, then PUT /v1/me/links/{provider}. */
    fun link(provider: BipeLinkProvider, confirmOwner: suspend () -> Boolean) {
        scope.launch {
            if (!confirmOwner()) {
                // A phone with no screen lock cannot pass the check; a cancelled prompt is no error.
                if (!OwnerCheck.canCheckOwner(context)) _state.update { it.copy(error = R.string.bipe_linked_account_error) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.LINKING, error = null) }
            val error = runLink(context, model.api, provider) { model.session.accessToken() }
            if (error == null) dismissOwnEvents()
            _state.update { it.copy(phase = Phase.SHOWN, error = error) }
            load()
        }
    }

    /** Action unlink: confirmed by the dialog, then the owner check, then DELETE /v1/me/links/{provider}. */
    fun unlink(provider: BipeLinkProvider, confirmOwner: suspend () -> Boolean) {
        scope.launch {
            if (!confirmOwner()) {
                if (!OwnerCheck.canCheckOwner(context)) _state.update { it.copy(error = R.string.bipe_linked_account_unlink_error) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.UNLINKING, error = null) }
            val ok = runCatching { model.authorized { model.api.unlink(provider, it) } }.isSuccess
            if (ok) {
                dismissOwnEvents()
                Toast.makeText(context, R.string.bipe_linked_account_unlinked, Toast.LENGTH_SHORT).show()
            }
            _state.update { it.copy(phase = Phase.SHOWN, error = if (ok) null else R.string.bipe_linked_account_unlink_error) }
            load()
        }
    }

    /** A change made on this phone needs no recovery-alert notice here. */
    private suspend fun dismissOwnEvents() {
        val events = runCatching { model.authorized { model.api.account(it) } }.getOrNull()?.linkEvents.orEmpty()
        RecoveryAlertModel.dismissOnThisPhone(context, model.session.userId, events.take(1).map { it.id })
    }

    companion object {
        /**
         * The link steps after the owner check: the web flow and PUT /v1/me/links/{provider}. Null when linked or when
         * the person closed the tab (the row stays unlinked), else the string resource of the error.
         */
        suspend fun runLink(context: Context, api: BipeApiClient, provider: BipeLinkProvider, accessToken: suspend () -> String?): Int? = try {
            val start = BipeApiClient.withTokenRetry(accessToken) { api.startLinkWebFlow(provider, LinkWebFlow.appId, it) }
            when (val result = LinkWebFlow.run(context, start)) {
                is LinkWebFlow.Result.Proof -> {
                    BipeApiClient.withTokenRetry(accessToken) { api.link(provider, result.proof, it) }
                    null
                }
                LinkWebFlow.Result.Cancelled -> null
                LinkWebFlow.Result.Failed -> R.string.bipe_linked_account_error
            }
        } catch (error: BipeApiException) {
            when {
                (error as? BipeApiException.Server)?.code == BipeApiErrorCode.LINK_TAKEN -> R.string.bipe_linked_account_taken
                error is BipeApiException.Network -> R.string.bipe_error_network
                else -> R.string.bipe_linked_account_error
            }
        }
    }
}

/** Spec linked-accounts. */
@Composable
fun LinkedAccountsScreen(model: SettingsModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linked = remember(model) { LinkedAccountsModel(context.applicationContext, model, scope) }
    val state by linked.state.collectAsState()
    val colors = BipeTheme.colors
    var confirmUnlink by remember { mutableStateOf<BipeLinkProvider?>(null) }
    val linkReason = stringResource(R.string.bipe_linked_account_biometric)
    val unlinkReason = stringResource(R.string.bipe_linked_account_unlink_biometric)
    LaunchedEffect(linked) { linked.appear() }
    SettingsPage(stringResource(R.string.bipe_linked_accounts_title), BipeScreen.LINKED_ACCOUNTS.id, onBack) {
        item(key = "body") {
            Text(
                stringResource(R.string.bipe_linked_account_body, BipeRegistries.LINKED_RECOVERY_DELAY_HOURS.toString()) + "\n\n" +
                    stringResource(R.string.bipe_linked_account_no_pin_reset),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(state.rows, key = { it.provider.id }) { row ->
            val busy = state.phase == LinkedAccountsModel.Phase.LINKING || state.phase == LinkedAccountsModel.Phase.UNLINKING
            if (row.linked) {
                SettingsRowItem(
                    title = stringResource(if (row.provider == BipeLinkProvider.APPLE) R.string.bipe_linked_apple else R.string.bipe_linked_google),
                    value = stringResource(R.string.bipe_linked_account_unlink),
                    valueColor = colors.trustDangerInk,
                    onClick = if (busy) null else ({ confirmUnlink = row.provider }),
                )
            } else {
                SettingsRowItem(
                    title = stringResource(if (row.provider == BipeLinkProvider.APPLE) R.string.bipe_link_apple else R.string.bipe_link_google),
                    titleColor = colors.accentInk,
                    onClick = if (busy) null else ({ linked.link(row.provider) { ownerCheck(context, linkReason) } }),
                )
            }
        }
        if (state.phase == LinkedAccountsModel.Phase.LOADING || state.phase == LinkedAccountsModel.Phase.LINKING || state.phase == LinkedAccountsModel.Phase.UNLINKING) {
            item(key = "progress") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
            }
        }
        state.error?.let { error ->
            item(key = "error") {
                Text(stringResource(error), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
            }
        }
    }
    confirmUnlink?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmUnlink = null },
            title = { Text(stringResource(R.string.bipe_linked_account_unlink_title)) },
            text = { Text(stringResource(R.string.bipe_linked_account_unlink_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = null
                    linked.unlink(provider) { ownerCheck(context, unlinkReason) }
                }) { Text(stringResource(R.string.bipe_linked_account_unlink), color = colors.trustDangerInk) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlink = null }) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
}

/**
 * The owner check before a link change (security rule 14, tier 3): biometrics or the screen lock. A phone with no
 * screen lock cannot pass it (fail closed, as in data-export).
 */
private suspend fun ownerCheck(context: Context, reason: String): Boolean = OwnerCheck.owner(context, reason)
