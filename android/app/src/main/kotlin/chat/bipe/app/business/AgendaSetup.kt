// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupFooter
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.settings.SettingsRowItem
import chat.bipe.app.settings.SettingsSwitchItem
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.AGENDA_INCOMPLETE
import chat.bipe.shared.api.BipeAgendaInput
import chat.bipe.shared.api.BipeAgendaPolicy
import chat.bipe.shared.api.BipeAgendaState
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeAvailability
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.activateAgenda
import chat.bipe.shared.api.agendaAvailability
import chat.bipe.shared.api.createAgenda
import chat.bipe.shared.api.deleteAgenda
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.replaceAgenda
import chat.bipe.shared.api.setAgendaAvailability
import chat.bipe.shared.api.setAgendaPolicy
import chat.bipe.shared.api.setAgendaSettings
import chat.bipe.shared.generated.BipeAgendaTemplates
import chat.bipe.shared.generated.BipeScreen
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The titles of the generated agenda templates (BipeAgendaTemplates titleKey). */
private val TEMPLATE_TITLES = mapOf(
    "bipe_agenda_template_pet_grooming" to R.string.bipe_agenda_template_pet_grooming,
    "bipe_agenda_template_barbershop" to R.string.bipe_agenda_template_barbershop,
    "bipe_agenda_template_salon" to R.string.bipe_agenda_template_salon,
    "bipe_agenda_template_clinic" to R.string.bipe_agenda_template_clinic,
    "bipe_agenda_template_classes" to R.string.bipe_agenda_template_classes,
    "bipe_agenda_template_other" to R.string.bipe_agenda_template_other,
)

/** Type DayHours of spec agenda-setup: one ISO weekday, open or closed, with its local ranges. */
data class DayHours(val weekday: Int, val open: Boolean, val ranges: List<Pair<LocalTime, LocalTime>>) {
    /** Each range ends after it starts and none overlap. */
    val isValid: Boolean
        get() = !open || (ranges.isNotEmpty() && ranges.sortedBy { it.first }.let { sorted ->
            sorted.all { it.second.isAfter(it.first) } && sorted.zipWithNext().all { (a, b) -> !b.first.isBefore(a.second) }
        })
}

/** Spec agenda-setup. */
class AgendaSetupModel(private val context: Context, private val business: BusinessApi, private val scope: CoroutineScope) {
    enum class Step { TEMPLATE, HOURS, PEOPLE, SERVICES, PUBLISH }

    var step by mutableStateOf(Step.TEMPLATE)
    var loading by mutableStateOf(true)
    var state by mutableStateOf<BipeAgendaState?>(null)
    var agendaId by mutableStateOf<String?>(null)
    val hours = mutableStateListOf<DayHours>()
    var hoursLoading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun start(startAtHours: Boolean) {
        scope.launch {
            loading = true
            state = runCatching { business.withToken { myAgenda(it) } }.getOrNull()
            loading = false
            if (state != null || startAtHours) showHours(state?.agendas?.firstOrNull()?.id)
        }
    }

    /** Action pickTemplate: activates with the template and the phone's time zone. */
    fun pickTemplate(templateId: String) = call {
        state = business.withToken { activateAgenda(templateId, ZoneId.systemDefault().id, it) }
        showHours(state?.agendas?.firstOrNull()?.id)
    }

    fun showHours(id: String?) {
        step = Step.HOURS
        agendaId = id
        if (id == null) return
        hoursLoading = true
        scope.launch {
            val rules = runCatching { business.withToken { agendaAvailability(id, it) } }.getOrNull().orEmpty()
            hours.clear()
            hours.addAll((1..7).map { weekday ->
                val ranges = rules.filter { it.weekday == weekday }.mapNotNull { rule ->
                    val start = runCatching { LocalTime.parse(rule.start) }.getOrNull()
                    val end = runCatching { LocalTime.parse(rule.end) }.getOrNull()
                    if (start != null && end != null) start to end else null
                }
                DayHours(weekday, ranges.isNotEmpty(), ranges)
            })
            hoursLoading = false
        }
    }

    /** Action setDay. A day turned on gets 09:00 to 18:00 to start with. */
    fun setDay(weekday: Int, open: Boolean) = update(weekday) {
        it.copy(open = open, ranges = if (open && it.ranges.isEmpty()) listOf(LocalTime.of(9, 0) to LocalTime.of(18, 0)) else it.ranges)
    }

    /** Action addRange: after the last range of the day. */
    fun addRange(weekday: Int) = update(weekday) { day ->
        val last = day.ranges.maxByOrNull { it.second }?.second ?: LocalTime.of(8, 0)
        val start = if (last.isBefore(LocalTime.of(22, 0))) last.plusHours(1) else LocalTime.of(22, 0)
        day.copy(open = true, ranges = day.ranges + (start to start.plusHours(1).let { if (it.isBefore(start)) LocalTime.of(23, 45) else it }))
    }

    fun setRange(weekday: Int, index: Int, range: Pair<LocalTime, LocalTime>) = update(weekday) { day ->
        day.copy(ranges = day.ranges.mapIndexed { i, old -> if (i == index) range else old })
    }

    fun removeRange(weekday: Int, index: Int) = update(weekday) { day ->
        val ranges = day.ranges.filterIndexed { i, _ -> i != index }
        day.copy(ranges = ranges, open = ranges.isNotEmpty())
    }

    private fun update(weekday: Int, change: (DayHours) -> DayHours) {
        val index = hours.indexOfFirst { it.weekday == weekday }
        if (index >= 0) hours[index] = change(hours[index])
    }

    /** Action saveHours, then the people step. */
    fun saveHours() {
        val id = agendaId ?: return
        call {
            val rules = hours.filter { it.open }.flatMap { day ->
                day.ranges.map { (start, end) -> BipeAvailability(day.weekday, start.toString(), end.toString()) }
            }
            business.withToken { setAgendaAvailability(id, rules, it) }
            step = Step.PEOPLE
        }
    }

    /** Action addPerson. The person needs no Bipe account (ADR 0099). */
    fun addPerson(name: String) = call {
        val sort = (state?.agendas?.maxOfOrNull { it.sort } ?: -1) + 1
        business.withToken { createAgenda(BipeAgendaInput(name.trim(), active = true, sort = sort), it) }
        reload()
    }

    fun renamePerson(agendaId: String, name: String) = call {
        val agenda = state?.agendas?.firstOrNull { it.id == agendaId } ?: return@call
        business.withToken { replaceAgenda(agendaId, BipeAgendaInput(name.trim(), agenda.active, agenda.sort), it) }
        reload()
    }

    /** Action removePerson. Bookings keep it. */
    fun removePerson(agendaId: String) = call {
        business.withToken { deleteAgenda(agendaId, it) }
        reload()
    }

    suspend fun reload() {
        state = business.withToken { myAgenda(it) }
    }

    fun refresh() = call { reload() }

    /** Action publish. bad_request incomplete shows bipe_agenda_publish_incomplete. */
    fun publish(onDone: () -> Unit) {
        val current = state ?: return
        saving = true
        error = null
        scope.launch {
            try {
                state = business.withToken { setAgendaSettings(true, current.timeZone, it) }
                Toast.makeText(context, context.getString(R.string.bipe_agenda_setup_done), Toast.LENGTH_LONG).show()
                onDone()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = publishError(context, failure)
            }
            saving = false
        }
    }

    fun back(close: () -> Unit) {
        error = null
        step = when (step) {
            Step.TEMPLATE, Step.HOURS -> return close()
            Step.PEOPLE -> Step.HOURS
            Step.SERVICES -> Step.PEOPLE
            Step.PUBLISH -> Step.SERVICES
        }
    }

    private fun call(block: suspend () -> Unit) {
        saving = true
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = context.getString(R.string.bipe_agenda_error_save)
            }
            saving = false
        }
    }
}

private fun publishError(context: Context, failure: Throwable): String =
    if (failure is BipeApiException.Server && failure.code == BipeApiErrorCode.BAD_REQUEST && failure.reason == AGENDA_INCOMPLETE) {
        context.getString(R.string.bipe_agenda_publish_incomplete)
    } else {
        context.getString(R.string.bipe_agenda_error_save)
    }

/**
 * Spec agenda-setup: template, opening hours per day with ranges, who works, the services, then Publicar. An agenda that
 * is already on starts at the hours step ([startAtHours] also when agenda-settings opens it).
 */
@Composable
fun AgendaSetupScreen(business: BusinessApi, startAtHours: Boolean, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { AgendaSetupModel(context.applicationContext, business, scope) }
    LaunchedEffect(model) { model.start(startAtHours) }
    BackHandler { model.back(onClose) }
    var editing by remember { mutableStateOf<BipeCatalogItem?>(null) }
    var addingService by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_SETUP.id)) {
        GroupTopBar(stringResource(R.string.bipe_agenda_activate), onBack = { model.back(onClose) })
        if (model.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (model.step) {
                AgendaSetupModel.Step.TEMPLATE -> TemplateStep(model)
                AgendaSetupModel.Step.HOURS -> HoursStep(model)
                AgendaSetupModel.Step.PEOPLE -> PeopleStep(model)
                AgendaSetupModel.Step.SERVICES -> ServicesStep(model, onEdit = { editing = it }, onAdd = { addingService = true })
                AgendaSetupModel.Step.PUBLISH -> {
                    GroupSectionHeader(stringResource(R.string.bipe_agenda_publish))
                    GroupFooter(stringResource(R.string.bipe_agenda_template_hint))
                }
            }
            model.error?.let { Text(it, color = colors.trustDangerInk, modifier = Modifier.padding(16.dp)) }
        }
        val step = model.step
        if (step != AgendaSetupModel.Step.TEMPLATE) {
            Button(
                enabled = !model.saving && (step != AgendaSetupModel.Step.HOURS || (!model.hoursLoading && model.hours.all { it.isValid })),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                onClick = {
                    when (step) {
                        AgendaSetupModel.Step.HOURS -> model.saveHours()
                        AgendaSetupModel.Step.PEOPLE -> model.step = AgendaSetupModel.Step.SERVICES
                        AgendaSetupModel.Step.SERVICES -> model.step = AgendaSetupModel.Step.PUBLISH
                        AgendaSetupModel.Step.PUBLISH -> model.publish(onClose)
                        AgendaSetupModel.Step.TEMPLATE -> Unit
                    }
                },
            ) {
                if (model.saving) CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
                else Text(stringResource(if (step == AgendaSetupModel.Step.PUBLISH) R.string.bipe_agenda_publish else R.string.bipe_action_continue))
            }
        }
    }
    val state = model.state
    if ((editing != null || addingService) && state != null) {
        BusinessFullScreen(onDismiss = { editing = null; addingService = false }) {
            CatalogItemEditScreen(
                business = business,
                item = editing,
                agendas = state.agendas,
                nextSort = (state.services.maxOfOrNull { it.sort } ?: -1) + 1,
                initialKind = BipeCatalogItem.SERVICE,
                onClose = { changed ->
                    editing = null
                    addingService = false
                    if (changed) model.refresh()
                },
            )
        }
    }
}

@Composable
private fun TemplateStep(model: AgendaSetupModel) {
    val colors = BipeTheme.colors
    GroupSectionHeader(stringResource(R.string.bipe_agenda_pick_template))
    BipeAgendaTemplates.templates.forEach { template ->
        val title = TEMPLATE_TITLES[template.titleKey]?.let { stringResource(it) } ?: template.id
        ListItem(
            headlineContent = { Text(title, color = colors.inkPrimary) },
            colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
            modifier = Modifier.clickable(enabled = !model.saving) { model.pickTemplate(template.id) },
        )
        HorizontalDivider(color = colors.lineSubtle)
    }
    GroupFooter(stringResource(R.string.bipe_agenda_template_hint))
    if (model.saving) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoursStep(model: AgendaSetupModel) {
    val colors = BipeTheme.colors
    val times = remember { dayTimes(15) }
    GroupSectionHeader(stringResource(R.string.bipe_agenda_setup_hours))
    val agendas = model.state?.agendas.orEmpty()
    if (agendas.size > 1) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            agendas.forEach { agenda ->
                FilterChip(selected = model.agendaId == agenda.id, onClick = { model.showHours(agenda.id) }, label = { Text(agenda.name) })
            }
        }
    }
    if (model.hoursLoading) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
        return
    }
    model.hours.forEach { day ->
        val name = DayOfWeek.of(day.weekday).getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                if (!day.open) Text(stringResource(R.string.bipe_agenda_closed), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            Switch(checked = day.open, onCheckedChange = { model.setDay(day.weekday, it) })
        }
        if (day.open) {
            day.ranges.forEachIndexed { index, (start, end) ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PickerField(stringResource(R.string.bipe_agenda_time), BusinessText.clock(start), times.map { it to BusinessText.clock(it) }) {
                            model.setRange(day.weekday, index, it to end)
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.weight(1f)) {
                        PickerField(stringResource(R.string.bipe_agenda_time), BusinessText.clock(end), times.map { it to BusinessText.clock(it) }) {
                            model.setRange(day.weekday, index, start to it)
                        }
                    }
                    val range = BusinessText.clock(start) + "–" + BusinessText.clock(end)
                    IconButton(onClick = { model.removeRange(day.weekday, index) }) {
                        Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_a11y_remove_person, range), tint = colors.glyphSecondary)
                    }
                }
            }
            if (!day.isValid) Text(stringResource(R.string.bipe_agenda_error_save), color = colors.trustDangerInk, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { model.addRange(day.weekday) }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.bipe_agenda_add_range)) }
        }
        HorizontalDivider(color = colors.lineSubtle)
    }
}

@Composable
private fun PeopleStep(model: AgendaSetupModel) {
    val colors = BipeTheme.colors
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    GroupSectionHeader(stringResource(R.string.bipe_agenda_setup_people))
    val agendas = model.state?.agendas.orEmpty()
    agendas.forEach { agenda ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(agenda.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = { naming = agenda.id to agenda.name }, enabled = !model.saving) { Text(stringResource(R.string.bipe_app_edit)) }
            if (agendas.size > 1) {
                TextButton(onClick = { model.removePerson(agenda.id) }, enabled = !model.saving) {
                    Text(stringResource(R.string.bipe_agenda_remove_person), color = colors.trustDangerInk)
                }
            }
        }
        HorizontalDivider(color = colors.lineSubtle)
    }
    TextButton(onClick = { naming = null to "" }, enabled = !model.saving, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.bipe_agenda_add_person))
    }
    naming?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(if (id == null) R.string.bipe_agenda_add_person else R.string.bipe_app_edit)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.bipe_agenda_person_name)) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (id == null) model.addPerson(name) else model.renamePerson(id, name)
                    naming = null
                }) { Text(stringResource(R.string.bipe_app_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
            containerColor = colors.surfaceRaised,
        )
    }
}

@Composable
private fun ServicesStep(model: AgendaSetupModel, onEdit: (BipeCatalogItem) -> Unit, onAdd: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    GroupSectionHeader(stringResource(R.string.bipe_agenda_setup_review))
    val state = model.state ?: return
    state.services.forEach { service ->
        val people = state.agendas.filter { it.id in service.agendaIds.orEmpty() }.joinToString { it.name }
        ListItem(
            headlineContent = { Text(service.name, color = colors.inkPrimary) },
            supportingContent = {
                Text(
                    listOfNotNull(service.durationMinutes?.let { BusinessText.duration(context, it) }, BusinessText.price(context, service.price), people.ifBlank { null })
                        .joinToString(" · "),
                    color = colors.inkSecondary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
            modifier = Modifier.clickable { onEdit(service) },
        )
        HorizontalDivider(color = colors.lineSubtle)
    }
    TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.bipe_agenda_add_service)) }
    GroupFooter(stringResource(R.string.bipe_agenda_template_hint))
}

/**
 * Spec agenda-settings: publish state and the booking policy. reminder_minutes goes back as read. The hours and the
 * people open agenda-setup at the hours step.
 */
@Composable
fun AgendaSettingsScreen(business: BusinessApi, agenda: BipeAgendaState, onClose: () -> Unit, onEditSetup: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var published by remember { mutableStateOf(agenda.published) }
    val policy = agenda.policy
    var minNotice by remember { mutableStateOf(policy.minNoticeMinutes.toString()) }
    var horizon by remember { mutableStateOf(policy.horizonDays.toString()) }
    var step by remember { mutableStateOf(policy.slotStepMinutes.toString()) }
    var cancelWindow by remember { mutableStateOf(policy.cancelWindowMinutes.toString()) }
    var maxOpen by remember { mutableStateOf(policy.maxOpenBookingsPerCustomer.toString()) }
    var requestMode by remember { mutableStateOf(policy.requestMode) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val edited = BipeAgendaPolicy(
        minNoticeMinutes = minNotice.toIntOrNull() ?: -1,
        horizonDays = horizon.toIntOrNull() ?: -1,
        slotStepMinutes = step.toIntOrNull() ?: -1,
        cancelWindowMinutes = cancelWindow.toIntOrNull() ?: -1,
        confirmMode = if (requestMode) BipeAgendaPolicy.REQUEST else BipeAgendaPolicy.AUTO,
        maxOpenBookingsPerCustomer = maxOpen.toIntOrNull() ?: -1,
        reminderMinutes = policy.reminderMinutes,
    )
    // The ranges bipe-api takes.
    val valid = edited.minNoticeMinutes in 0..43_200 && edited.horizonDays in 1..365 && edited.slotStepMinutes in 5..240 &&
        edited.cancelWindowMinutes in 0..43_200 && edited.maxOpenBookingsPerCustomer in 1..20

    Column(Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_SETTINGS.id)) {
        GroupTopBar(stringResource(R.string.bipe_agenda_settings_title), onBack = onClose) {
            if (saving) {
                CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
            } else {
                TextButton(enabled = valid, onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        runCatching { business.withToken { setAgendaPolicy(edited, it) } }
                            .onSuccess { onClose() }
                            .onFailure { error = context.getString(R.string.bipe_agenda_error_save) }
                        saving = false
                    }
                }) { Text(stringResource(R.string.bipe_app_save)) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsRowItem(
                title = stringResource(if (published) R.string.bipe_agenda_published else R.string.bipe_agenda_not_published),
                valueColor = null,
                onClick = null,
            )
            TextButton(enabled = !saving, modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                saving = true
                error = null
                scope.launch {
                    try {
                        published = business.withToken { setAgendaSettings(!published, agenda.timeZone, it) }.published
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = publishError(context, failure)
                    }
                    saving = false
                }
            }) {
                Text(stringResource(if (published) R.string.bipe_agenda_unpublish else R.string.bipe_agenda_publish),
                    color = if (published) colors.trustDangerInk else colors.accentInk)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NumberField(stringResource(R.string.bipe_agenda_min_notice), minNotice, !saving) { minNotice = it }
                NumberField(stringResource(R.string.bipe_agenda_horizon), horizon, !saving) { horizon = it }
                NumberField(stringResource(R.string.bipe_agenda_slot_step), step, !saving) { step = it }
                NumberField(stringResource(R.string.bipe_agenda_cancel_window), cancelWindow, !saving) { cancelWindow = it }
                NumberField(stringResource(R.string.bipe_agenda_max_open), maxOpen, !saving) { maxOpen = it }
            }
            SettingsSwitchItem(stringResource(R.string.bipe_agenda_request_mode), stringResource(R.string.bipe_agenda_request_mode_footer), requestMode, !saving) {
                requestMode = it
            }
            SettingsRowItem(title = stringResource(R.string.bipe_agenda_time_zone), value = agenda.timeZone, onClick = null)
            SettingsRowItem(title = stringResource(R.string.bipe_agenda_setup_hours), showsChevron = true, onClick = onEditSetup)
            SettingsRowItem(title = stringResource(R.string.bipe_agenda_setup_people), showsChevron = true, onClick = onEditSetup)
            error?.let { Text(it, color = colors.trustDangerInk, modifier = Modifier.padding(16.dp)) }
            Spacer(Modifier.size(24.dp))
        }
    }
}
