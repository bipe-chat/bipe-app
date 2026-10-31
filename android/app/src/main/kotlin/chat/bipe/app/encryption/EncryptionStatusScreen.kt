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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.PromptAction
import chat.bipe.app.chatlist.PromptCard
import chat.bipe.app.common.edgeToEdge
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** The account's encryption-status model, for the oldMessages settings row. Null outside HomeScreen. */
val LocalEncryptionStatus = staticCompositionLocalOf<EncryptionStatusModel?> { null }

/** EncryptionStatusModel.needsApproval, from the current state. */
@Composable
fun EncryptionStatusModel.collectNeedsApproval(): Boolean {
    val verificationState by verificationState.collectAsState()
    val recoveryState by recoveryState.collectAsState()
    val hasOtherDevices by hasOtherDevices.collectAsState()
    val seesUndecryptable by seesUndecryptable.collectAsState()
    return needsApproval(verificationState, recoveryState, hasOtherDevices, seesUndecryptable)
}

/** The approvePhone entry of chat-list-prompt (bipe-chat/bipe#894) holds, from the current state. */
@Composable
fun EncryptionStatusModel.collectShowsCard(): Boolean {
    val verificationState by verificationState.collectAsState()
    val recoveryState by recoveryState.collectAsState()
    val hasOtherDevices by hasOtherDevices.collectAsState()
    val seesUndecryptable by seesUndecryptable.collectAsState()
    val bannerDismissed by bannerDismissed.collectAsState()
    return showsBanner(verificationState, recoveryState, hasOtherDevices, seesUndecryptable, bannerDismissed)
}

/** The approvePhone card of chat-list-prompt. The primary is the one way this phone has. */
@Composable
fun ApprovePhoneCard(model: EncryptionStatusModel) {
    val recoveryState by model.recoveryState.collectAsState()
    val hasOtherDevices by model.hasOtherDevices.collectAsState()
    val wayOut = model.hasWayOut(recoveryState, hasOtherDevices)
    PromptCard(
        title = stringResource(R.string.bipe_app_history_title),
        body = stringResource(if (wayOut) R.string.bipe_app_history_banner else R.string.bipe_app_history_none),
        primary = when {
            hasOtherDevices -> PromptAction(stringResource(R.string.bipe_app_history_approve), model::approveWithDevice)
            model.canUseRecoveryKey(recoveryState) -> PromptAction(stringResource(R.string.bipe_app_history_use_key), model::useRecoveryKey)
            else -> null
        },
        texts = if (wayOut) emptyList() else listOf(PromptAction(stringResource(R.string.bipe_fallback_skip), model::dismiss)),
        modifier = Modifier.testTag(BipeScreen.ENCRYPTION_STATUS.id),
    )
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
