// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.reachDate
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeReachStatus
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException

/** Phase of spec account-status. */
private sealed interface AccountStatusPhase {
    data object Loading : AccountStatusPhase
    data class Shown(val status: BipeReachStatus) : AccountStatusPhase
    data object Failed : AccountStatusPhase
}

/**
 * Spec account-status (security.md rule 11, bipe-chat/bipe#846): the first-contact tier, the budget used, when one
 * more frees up, and the step that raises the budget. The numbers come from GET /v1/me/reach.
 */
@Composable
fun AccountStatusScreen(model: SettingsModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    var phase by remember { mutableStateOf<AccountStatusPhase>(AccountStatusPhase.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    // Actions appear and retry.
    LaunchedEffect(attempt) {
        phase = try {
            AccountStatusPhase.Shown(model.authorized { model.api.reach(it) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AccountStatusPhase.Failed
        }
    }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    SettingsPage(stringResource(R.string.bipe_account_status_title), BipeScreen.ACCOUNT_STATUS.id, onBack) {
        when (val current = phase) {
            AccountStatusPhase.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            AccountStatusPhase.Failed -> {
                item(key = "error") {
                    Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
                }
                item(key = "retry") {
                    TextButton(onClick = {
                        phase = AccountStatusPhase.Loading
                        attempt += 1
                    }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.bipe_app_retry)) }
                }
            }
            is AccountStatusPhase.Shown -> {
                val status = current.status
                item(key = "tier") {
                    SettingsRowItem(
                        title = stringResource(R.string.bipe_account_status_tier),
                        value = stringResource(tierName(status.tier)),
                        onClick = null,
                    )
                }
                item(key = "used") {
                    SettingsRowItem(
                        title = stringResource(R.string.bipe_account_status_used, numbers.format(status.used), numbers.format(status.budget)),
                        footer = status.resetsAt?.let { stringResource(R.string.bipe_account_status_resets, reachDate(context, it)) },
                        onClick = null,
                    )
                }
                raiseLine(status.raiseBy)?.let { line ->
                    item(key = "raise") { Text(stringResource(line), modifier = Modifier.padding(16.dp)) }
                }
                item(key = "footer") {
                    Text(
                        stringResource(R.string.bipe_account_status_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun tierName(tier: BipeReachStatus.Tier): Int = when (tier) {
    BipeReachStatus.Tier.UNATTESTED -> R.string.bipe_account_status_tier_unattested
    BipeReachStatus.Tier.NEW -> R.string.bipe_account_status_tier_new
    BipeReachStatus.Tier.NORMAL -> R.string.bipe_account_status_tier_normal
    BipeReachStatus.Tier.VERIFIED -> R.string.bipe_account_status_tier_verified
    BipeReachStatus.Tier.COMPANY -> R.string.bipe_account_status_tier_company
    BipeReachStatus.Tier.SLOWED -> R.string.bipe_account_status_tier_slowed
}

private fun raiseLine(raiseBy: BipeReachStatus.RaiseBy?): Int? = when (raiseBy) {
    BipeReachStatus.RaiseBy.VERIFY -> R.string.bipe_account_status_raise_verify
    BipeReachStatus.RaiseBy.WAIT -> R.string.bipe_account_status_raise_wait
    null -> null
}
