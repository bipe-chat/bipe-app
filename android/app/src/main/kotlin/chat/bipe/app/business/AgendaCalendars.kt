// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAgenda
import chat.bipe.shared.api.BipeBusyImport
import chat.bipe.shared.api.addBusyImport
import chat.bipe.shared.api.busyImports
import chat.bipe.shared.api.issueAgendaFeed
import chat.bipe.shared.api.removeBusyImport
import kotlinx.coroutines.launch

/** Spec agenda-settings (ADR 0101): per agenda, the secret feed for a calendar app and the busy-time imports. */
@Composable
fun AgendaCalendarsSections(business: BusinessApi, agendas: List<BipeAgenda>) {
    agendas.forEach { agenda -> AgendaCalendarSection(business, agenda, agendas.size > 1) }
}

@Composable
private fun AgendaCalendarSection(business: BusinessApi, agenda: BipeAgenda, showsName: Boolean) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imports by remember { mutableStateOf<List<BipeBusyImport>>(emptyList()) }
    var includeFirstName by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.bipe_agenda_busy_invalid)
    val generic = stringResource(R.string.bipe_error_generic)
    suspend fun load() {
        runCatching { business.withToken { busyImports(agenda.id, it) } }.getOrNull()?.let { imports = it }
    }
    LaunchedEffect(agenda.id) { load() }
    val title = stringResource(R.string.bipe_agenda_calendar_section)
    GroupSectionHeader(if (showsName) "$title · ${agenda.name}" else title)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bipe_agenda_feed_first_name), color = colors.inkPrimary, modifier = Modifier.weight(1f))
            Switch(checked = includeFirstName, onCheckedChange = { includeFirstName = it })
        }
        TextButton(onClick = {
            scope.launch {
                val feed = runCatching { business.withToken { issueAgendaFeed(agenda.id, includeFirstName, it) } }.getOrNull()
                if (feed == null) {
                    error = generic
                } else {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, feed.url)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
        }) { Text(stringResource(R.string.bipe_agenda_feed_get)) }
        imports.forEach { item ->
            TextButton(onClick = {
                scope.launch {
                    runCatching { business.withToken { removeBusyImport(agenda.id, item.id, it) } }
                    load()
                }
            }) { Text(stringResource(R.string.bipe_agenda_busy_remove), color = colors.trustDangerInk) }
            if (item.failing) {
                Text(stringResource(R.string.bipe_agenda_busy_failing), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
            }
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.bipe_agenda_busy_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = address.isNotBlank(), onClick = {
            scope.launch {
                error = null
                val made = runCatching { business.withToken { addBusyImport(agenda.id, address.trim(), it) } }.getOrNull()
                if (made == null) {
                    error = invalid
                } else {
                    address = ""
                    load()
                }
            }
        }) { Text(stringResource(R.string.bipe_agenda_busy_add)) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
        Text(
            stringResource(R.string.bipe_agenda_feed_footer) + " " + stringResource(R.string.bipe_agenda_busy_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
    }
}
