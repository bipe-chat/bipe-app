// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.signup.TermsRow
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec terms-reaccept (bipe-shared product/screens/terms-reaccept.json). */
class TermsReacceptModel(private val api: BipeApiClient, private val session: AccountSession, private val scope: CoroutineScope) {
    enum class Phase { HIDDEN, SHOWN, SAVING, FAILED }

    private val _phase = MutableStateFlow(Phase.HIDDEN)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _accepted = MutableStateFlow(false)
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()
    var acceptedVersion: String? = null
        private set
    var currentVersion: String = BipeRegistries.LEGAL_VERSION
        private set

    /** Action check: after sign-in and on foreground. A failed read shows nothing; the next check tries again. */
    fun check() {
        if (_phase.value != Phase.HIDDEN) return
        scope.launch {
            runCatching { api.signupConfig() }.getOrNull()?.termsVersion?.let { if (it > currentVersion) currentVersion = it }
            val status = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.account(token) }
            }.getOrNull() ?: return@launch
            acceptedVersion = status.termsVersion
            if (status.termsVersion != currentVersion) {
                _accepted.value = false
                _phase.value = Phase.SHOWN
            }
        }
    }

    /** Action toggle. */
    fun toggle() {
        _accepted.value = !_accepted.value
    }

    /** Action accept: never sends an acceptance the person did not tick. */
    fun accept() {
        if (!_accepted.value) return
        _phase.value = Phase.SAVING
        val version = currentVersion
        scope.launch {
            _phase.value = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.acceptTerms(version, token) }
            }.fold(onSuccess = { Phase.HIDDEN }, onFailure = { Phase.FAILED })
        }
    }
}

@Composable
fun TermsReacceptScreen(model: TermsReacceptModel, onSignOut: () -> Unit) {
    val colors = BipeTheme.colors
    val accepted by model.accepted.collectAsState()
    val phase by model.phase.collectAsState()
    var confirmsSignOut by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.TERMS_REACCEPT.id), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.bipe_terms_reaccept_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_terms_reaccept_body), color = colors.inkSecondary)
            TermsRow(accepted, onToggle = model::toggle)
            if (phase == TermsReacceptModel.Phase.FAILED) {
                Text(stringResource(R.string.bipe_terms_reaccept_failed), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
            }
            Spacer(Modifier.weight(1f))
            val enabled = accepted && phase != TermsReacceptModel.Phase.SAVING
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(if (enabled) colors.accentFill else colors.surfaceSunken, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = model::accept)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.bipe_action_continue), style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.inkOnAccent else colors.inkDisabled)
            }
            Text(
                stringResource(R.string.bipe_account_sign_out),
                color = colors.accentInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmsSignOut = true }.padding(vertical = 10.dp),
            )
        }
    }
    if (confirmsSignOut) {
        AlertDialog(
            onDismissRequest = { confirmsSignOut = false },
            text = { Text(stringResource(R.string.bipe_account_sign_out)) },
            confirmButton = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.bipe_account_sign_out), color = colors.trustDangerInk) } },
            dismissButton = { TextButton(onClick = { confirmsSignOut = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
}
