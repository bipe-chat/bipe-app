// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.media.DataUseMeter
import chat.bipe.app.media.StorageMeter
import chat.bipe.app.media.StorageUse
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen

/**
 * Spec data-use (bipe-chat/bipe#242): the budget of a normal day, the media counted today and this month by network,
 * the data saver switch, and the space Bipe uses on the phone (bipe-chat/bipe#826).
 */
@Composable
fun DataUseScreen(session: AccountSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    // mediaCacheBytes, voiceBytes and storeBytes; null while they load.
    var storage by remember { mutableStateOf<StorageUse?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    // Action measureStorage.
    LaunchedEffect(session) { storage = StorageMeter.measure(context.applicationContext, session.client) }
    val snapshot = remember { DataUseMeter.snapshot() }
    var saver by remember { mutableStateOf(DataUseMeter.saverOn(context)) }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val budget = size(BipeRegistries.DATA_USE_BUDGET_MEGABYTES_PER_DAY * 1_000_000L)
    val body = MaterialTheme.typography.bodyMedium

    @Composable
    fun countRow(title: String, count: DataUseMeter.Count) {
        ListItem(
            headlineContent = { Text(title, color = colors.inkPrimary) },
            supportingContent = {
                Text(
                    stringResource(R.string.bipe_data_use_sent, size(count.sentBytes)) + "\n" +
                        stringResource(R.string.bipe_data_use_received, size(count.receivedBytes)),
                    color = colors.inkSecondary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
    }

    SettingsPage(stringResource(R.string.bipe_chats_data_use), BipeScreen.DATA_USE.id, onBack) {
        item(key = "budget") {
            Text(stringResource(R.string.bipe_data_use_budget, budget), style = body, color = colors.inkPrimary, modifier = Modifier.padding(16.dp))
        }
        item(key = "today") {
            SectionHeader(stringResource(R.string.bipe_data_use_today))
            countRow(stringResource(R.string.bipe_data_use_mobile), snapshot.todayMobile)
            countRow(stringResource(R.string.bipe_data_use_wifi), snapshot.todayWifi)
        }
        item(key = "month") {
            SectionHeader(stringResource(R.string.bipe_data_use_month))
            countRow(stringResource(R.string.bipe_data_use_mobile), snapshot.monthMobile)
            countRow(stringResource(R.string.bipe_data_use_wifi), snapshot.monthWifi)
            Text(stringResource(R.string.bipe_data_use_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        item(key = "saver") {
            SectionHeader(null)
            val footer = stringResource(R.string.bipe_data_use_saver_footer) +
                if (DataUseMeter.systemSaverOn) "\n" + stringResource(R.string.bipe_data_use_saver_system) else ""
            SettingsSwitchItem(stringResource(R.string.bipe_data_use_saver), footer, saver) { isOn ->
                saver = isOn
                DataUseMeter.setSaver(context, isOn)
            }
        }
        // Spec data-use, storage: three sizes and the button that clears the media cache.
        item(key = "storage") {
            SectionHeader(stringResource(R.string.bipe_storage_title))
            listOf(
                R.string.bipe_storage_media to storage?.mediaCacheBytes,
                R.string.bipe_storage_voice to storage?.voiceBytes,
                R.string.bipe_storage_store to storage?.storeBytes,
            ).forEach { (title, bytes) ->
                ListItem(
                    headlineContent = { Text(stringResource(title), color = colors.inkPrimary) },
                    trailingContent = {
                        if (bytes != null) Text(size(bytes), color = colors.inkSecondary) else CircularProgressIndicator()
                    },
                    colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                )
            }
            // Action askClearMediaCache.
            SettingsRowItem(
                title = stringResource(R.string.bipe_storage_clear),
                titleColor = colors.trustDangerInk,
                onClick = if (clearing) null else ({ confirmClear = true }),
            )
            if (clearing) CircularProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp))
            Text(stringResource(R.string.bipe_storage_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (clearFailed) {
                Text(stringResource(R.string.bipe_error_generic), style = body, color = colors.trustDangerInk, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.bipe_storage_clear_title)) },
            text = { Text(stringResource(R.string.bipe_storage_clear_body)) },
            confirmButton = {
                // Action clearMediaCache. The session is built again, so the tabs build again and the app's notice
                // shows freedBytes (bipe_storage_cleared).
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    clearFailed = false
                    (context.applicationContext as BipeApplication).accountStart.clearCaches(session) {
                        clearFailed = true
                        clearing = false
                    }
                }) { Text(stringResource(R.string.bipe_storage_clear_confirm), color = colors.trustDangerInk) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
}
