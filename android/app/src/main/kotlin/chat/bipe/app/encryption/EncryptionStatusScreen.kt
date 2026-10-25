// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.edgeToEdge
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec encryption-status: the quiet banner above the chat list. Shows nothing when it does not apply. */
@Composable
fun HistoryBanner(model: EncryptionStatusModel) {
    val verificationState by model.verificationState.collectAsState()
    val recoveryState by model.recoveryState.collectAsState()
    val hasOtherDevices by model.hasOtherDevices.collectAsState()
    val bannerDismissed by model.bannerDismissed.collectAsState()
    if (!model.showsBanner(verificationState, recoveryState, hasOtherDevices, bannerDismissed)) return
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .testTag(BipeScreen.ENCRYPTION_STATUS.id)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = model::openSheet).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.bipe_app_history_banner), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
        }
        TextButton(onClick = model::dismiss) {
            Text(stringResource(R.string.bipe_fallback_skip), color = colors.accentInk)
        }
    }
}

/** The sheets of the encryption flow: encryption-status, device-verification and recovery-key-enter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionSheet(model: EncryptionStatusModel) {
    val route by model.route.collectAsState()
    val current = route ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = model::sheetClosed,
        sheetState = sheetState,
        containerColor = BipeTheme.colors.surfaceBase,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            when (current) {
                EncryptionStatusModel.Route.HISTORY -> HistorySheet(model)
                EncryptionStatusModel.Route.VERIFICATION -> DeviceVerificationScreen(model.verification, close = { model.show(null) })
                EncryptionStatusModel.Route.RECOVERY_KEY -> RecoveryKeyEnterScreen(
                    model,
                    close = { model.show(null) },
                    noKey = { model.show(EncryptionStatusModel.Route.HISTORY) },
                )
            }
        }
    }
}

/** Spec encryption-status: the sheet with the two ways to get old messages. */
@Composable
private fun HistorySheet(model: EncryptionStatusModel) {
    val colors = BipeTheme.colors
    val recoveryState by model.recoveryState.collectAsState()
    val hasOtherDevices by model.hasOtherDevices.collectAsState()
    val canUseKey = model.canUseRecoveryKey(recoveryState)
    Column(modifier = Modifier.testTag(BipeScreen.ENCRYPTION_STATUS.id), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.bipe_app_history_title), style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_recovery_history_note), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        if (hasOtherDevices || canUseKey) {
            // The ways back as a list, edge to edge past the 24 dp side padding of the sheet.
            Column(modifier = Modifier.edgeToEdge(24.dp).fillMaxWidth()) {
                if (hasOtherDevices) {
                    HistoryRow(PhosphorIcon.DEVICES, stringResource(R.string.bipe_app_history_approve), model::approveWithDevice)
                }
                if (hasOtherDevices && canUseKey) HorizontalDivider()
                if (canUseKey) {
                    HistoryRow(PhosphorIcon.KEY, stringResource(R.string.bipe_app_history_use_key), model::useRecoveryKey)
                }
            }
        }
        if (!hasOtherDevices && !canUseKey) {
            Text(stringResource(R.string.bipe_app_history_none), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        }
    }
}

@Composable
private fun HistoryRow(icon: PhosphorIcon, title: String, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary) },
        leadingContent = { Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(24.dp)) },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
