// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeWorkHours
import chat.bipe.shared.account.setWorkHours
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Spec work-hours (ADR 0138 rule 4, bipe-chat/bipe#511): the weekly opening hours of a work account. */
class WorkHoursModel(
    private val session: AccountSession,
    private val api: BipeApiClient,
    private val notifications: PhoneNotifications,
    private val scope: CoroutineScope,
    private val onSaved: (BipeWorkHours) -> Unit,
) {
    enum class Phase { LOADING, SHOWN, SAVING, FAILED }

    data class State(val hours: BipeWorkHours = BipeWorkHours.defaults(), val phase: Phase = Phase.LOADING)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear. */
    suspend fun appear() {
        val read = WorkHoursSync.refresh(session, api, notifications)
        _state.update { State(hours = read ?: it.hours, phase = Phase.SHOWN) }
    }

    /** Action setEnabled. */
    fun setEnabled(isOn: Boolean) = save(_state.value.hours.copy(enabled = isOn))

    /** The first range of a weekday, or null when closed. */
    fun range(day: Int): BipeWorkHours.Range? = _state.value.hours.days.firstOrNull { it.day == day }?.ranges?.firstOrNull()

    /** Action setDay: open or closed, with one range. A close time not after the open time is refused here. */
    fun setDay(day: Int, range: BipeWorkHours.Range?) {
        if (range != null) {
            val start = BipeWorkHours.minutes(range.start)
            val end = BipeWorkHours.minutes(range.end)
            if (start == null || end == null || end <= start) {
                _state.update { it.copy(phase = Phase.FAILED) }
                return
            }
        }
        val hours = _state.value.hours
        val days = hours.days.filter { it.day != day } + listOfNotNull(range?.let { BipeWorkHours.Day(day, listOf(it)) })
        save(hours.copy(days = days.sortedBy { it.day }))
    }

    private fun save(next: BipeWorkHours) {
        val old = _state.value.hours
        _state.value = State(next, Phase.SAVING)
        scope.launch {
            try {
                val saved = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.setWorkHours(next, it) }
                notifications.setWorkHours(session.userId, saved)
                onSaved(saved)
                _state.value = State(saved, Phase.SHOWN)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = State(old, Phase.FAILED)
            }
        }
    }
}

@Composable
fun WorkHoursScreen(
    session: AccountSession,
    api: BipeApiClient,
    notifications: PhoneNotifications,
    onSaved: (BipeWorkHours) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember(session) { WorkHoursModel(session, api, notifications, scope, onSaved) }
    LaunchedEffect(model) { model.appear() }
    val state by model.state.collectAsState()
    val colors = BipeTheme.colors
    val busy = state.phase == WorkHoursModel.Phase.LOADING || state.phase == WorkHoursModel.Phase.SAVING

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.WORK_HOURS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.bipe_work_hours_title), onBack)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.bipe_work_hours_enabled), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                    if (state.hours.enabled) {
                        val now = if (state.hours.isOpen(Instant.now())) R.string.bipe_work_hours_open_now else R.string.bipe_work_hours_closed_now
                        Text(stringResource(now), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                }
                Switch(checked = state.hours.enabled, onCheckedChange = model::setEnabled, enabled = !busy)
            }
            Text(
                stringResource(R.string.bipe_work_hours_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            (1..7).forEach { day ->
                WorkDayRow(day, state.hours.days.firstOrNull { it.day == day }?.ranges?.firstOrNull(), enabled = !busy) {
                    model.setDay(day, it)
                }
            }
            if (state.phase == WorkHoursModel.Phase.FAILED) {
                Text(
                    stringResource(R.string.bipe_work_hours_save_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.trustDangerInk,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** One weekday: open with its hours, or closed. ISO day 1 is Monday. */
@Composable
private fun WorkDayRow(day: Int, range: BipeWorkHours.Range?, enabled: Boolean, onChange: (BipeWorkHours.Range?) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val name = DayOfWeek.of(day).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    fun pick(hhmm: String, set: (String) -> Unit) {
        val minutes = (BipeWorkHours.minutes(hhmm) ?: 0) % (24 * 60)
        TimePickerDialog(context, { _, hour, minute -> set("%02d:%02d".format(Locale.ROOT, hour, minute)) },
            minutes / 60, minutes % 60, DateFormat.is24HourFormat(context)).show()
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                val value = range?.let { stringResource(R.string.bipe_work_hours_range, it.start, it.end) }
                    ?: stringResource(R.string.bipe_work_hours_closed)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            Switch(
                checked = range != null,
                onCheckedChange = { isOn -> onChange(if (isOn) BipeWorkHours.Range("08:00", "18:00") else null) },
                enabled = enabled,
            )
        }
        if (range != null) {
            TimeRow(stringResource(R.string.bipe_work_hours_opens), range.start, enabled) { pick(range.start) { onChange(range.copy(start = it)) } }
            TimeRow(stringResource(R.string.bipe_work_hours_closes), range.end, enabled) { pick(range.end) { onChange(range.copy(end = it)) } }
        }
    }
}

@Composable
private fun TimeRow(title: String, time: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
        Text(time, style = MaterialTheme.typography.bodyMedium, color = colors.accentInk)
    }
}
