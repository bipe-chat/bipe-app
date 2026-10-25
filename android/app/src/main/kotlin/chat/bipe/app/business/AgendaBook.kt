// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.ANY_AGENDA
import chat.bipe.shared.api.BipeAgenda
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBooking
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.BipeSlot
import chat.bipe.shared.api.BipeSlotDays
import chat.bipe.shared.api.agendaSlots
import chat.bipe.shared.api.confirmBooking
import chat.bipe.shared.api.holdBooking
import chat.bipe.shared.api.moveBooking
import chat.bipe.shared.api.publicAgendas
import chat.bipe.shared.api.publicServices
import chat.bipe.shared.generated.BipeScreen
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The cancel window the contract gives by default (Policy.cancel_window_minutes); the public routes do not say the business's own. */
private const val DEFAULT_CANCEL_WINDOW_MINUTES = 120

/**
 * Spec agenda-book: service, then person (skipped with one), then a day on a week strip and a free time, then review
 * with the hold. [moving] reschedules that booking: the review calls move instead of hold and confirm. [quoteAccept]
 * books the visit of a quote (spec quote-card acceptBooking): no hold, and the review calls it with the service, the
 * agenda and the start instead of confirm.
 */
class AgendaBookModel(
    private val context: Context,
    private val business: BusinessApi,
    val route: String,
    private val scope: CoroutineScope,
    val moving: BipeBooking?,
    val quoteAccept: (suspend (serviceId: String, agendaId: String, start: String) -> Unit)? = null,
) {
    enum class Step { SERVICE, PERSON, TIME, REVIEW }

    var step by mutableStateOf(Step.SERVICE)
    var loading by mutableStateOf(true)
    var loadFailed by mutableStateOf(false)
    var services by mutableStateOf<List<BipeCatalogItem>>(emptyList())
    var agendas by mutableStateOf<List<BipeAgenda>>(emptyList())
    var service by mutableStateOf<BipeCatalogItem?>(null)
    var agendaId by mutableStateOf<String?>(null)
    var weekStart by mutableStateOf(LocalDate.now())
    var week by mutableStateOf<BipeSlotDays?>(null)
    var weekLoading by mutableStateOf(false)
    var date by mutableStateOf<String?>(null)
    var slot by mutableStateOf<BipeSlot?>(null)
    var hold by mutableStateOf<BipeBooking?>(null)
    var working by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var weekJob: Job? = null

    /** The service is fixed (a move or a quote visit): back from the first step closes the flow. */
    private val fixedService: Boolean get() = moving != null || quoteAccept != null

    /** Today in the business time zone once the first week told it, else the phone's. */
    val today: LocalDate get() = LocalDate.now(week?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())

    fun start(preselectedServiceId: String?) {
        loading = true
        loadFailed = false
        scope.launch {
            try {
                services = business.api.publicServices(route)
                loading = false
                val preselected = preselectedServiceId?.let { id -> services.firstOrNull { it.id == id } }
                if (preselected != null) pickService(preselected) else if (fixedService) loadFailed = true
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                loading = false
                loadFailed = true
                error = BusinessText.error(context, failure, R.string.bipe_agenda_load_failed)
            }
        }
    }

    /** Action pickService. */
    fun pickService(item: BipeCatalogItem) {
        service = item
        error = null
        working = true
        scope.launch {
            try {
                agendas = business.api.publicAgendas(route, item.id)
                working = false
                if (agendas.size == 1) pickAgenda(agendas.first().id) else step = Step.PERSON
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                working = false
                this@AgendaBookModel.error = BusinessText.error(context, failure, R.string.bipe_agenda_load_failed)
            }
        }
    }

    /** Action pickAgenda: an agenda ID or [ANY_AGENDA]. */
    fun pickAgenda(id: String) {
        agendaId = id
        step = Step.TIME
        date = null
        showWeek(today)
    }

    /** Action showWeek: seven days that start at [start], one call. Never before today. */
    fun showWeek(start: LocalDate) {
        val service = service ?: return
        val agenda = agendaId ?: return
        val first = if (start.isBefore(today)) today else start
        weekStart = first
        weekLoading = true
        weekJob?.cancel()
        weekJob = scope.launch {
            try {
                val days = business.api.agendaSlots(route, service.id, agenda, first.toString(), first.plusDays(6).toString())
                week = days
                weekLoading = false
                if (date == null || days.days.none { it.date == date }) {
                    date = days.days.firstOrNull { it.slots.isNotEmpty() }?.date ?: days.days.firstOrNull()?.date
                }
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                weekLoading = false
                this@AgendaBookModel.error = BusinessText.error(context, failure, R.string.bipe_agenda_load_failed)
            }
        }
    }

    /** Action pickTime: holds the time (or, when moving, goes to review with it). slot_taken reloads the week. */
    fun pickTime(picked: BipeSlot) {
        val service = service ?: return
        val agenda = agendaId ?: return
        error = null
        slot = picked
        if (moving != null || quoteAccept != null) {
            step = Step.REVIEW
            return
        }
        working = true
        scope.launch {
            try {
                hold = business.withToken { holdBooking(route, service.id, agenda, picked.start, it) }
                step = Step.REVIEW
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                this@AgendaBookModel.error = BusinessText.error(context, failure)
                if (failure.isCode(BipeApiErrorCode.SLOT_TAKEN)) showWeek(weekStart)
            } finally {
                working = false
            }
        }
    }

    /** Action confirm: confirm the hold, move the booking, or accept the quote with this time. hold_expired goes back to time. */
    fun confirm(onDone: () -> Unit) {
        val picked = slot ?: return
        val agenda = agendaId ?: return
        working = true
        error = null
        scope.launch {
            try {
                val moving = moving
                val quoteAccept = quoteAccept
                val service = service
                if (quoteAccept != null && service != null) {
                    quoteAccept(service.id, agenda, picked.start)
                } else if (moving != null) {
                    business.withToken { moveBooking(route, moving.id, agenda, picked.start, it) }
                } else {
                    val held = hold ?: return@launch
                    business.withToken { confirmBooking(route, held.id, it) }
                }
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                this@AgendaBookModel.error = BusinessText.error(context, failure)
                if (failure.isCode(BipeApiErrorCode.HOLD_EXPIRED) || failure.isCode(BipeApiErrorCode.SLOT_TAKEN)) {
                    hold = null
                    step = Step.TIME
                    showWeek(weekStart)
                }
            } finally {
                working = false
            }
        }
    }

    /** Action back. Leaving review keeps nothing: an unconfirmed hold ends by itself after 5 minutes. */
    fun back(close: () -> Unit) {
        error = null
        when (step) {
            Step.SERVICE -> close()
            Step.PERSON -> if (fixedService) close() else step = Step.SERVICE
            Step.TIME -> step = when {
                agendas.size > 1 -> Step.PERSON
                fixedService -> return close()
                else -> Step.SERVICE
            }
            Step.REVIEW -> {
                hold = null
                step = Step.TIME
            }
        }
    }

    private fun Throwable.isCode(code: BipeApiErrorCode) = this is BipeApiException.Server && this.code == code
}

/** Spec agenda-book, inside a [BusinessFullScreen]. [onClose] says whether a booking was made or moved. */
@Composable
fun AgendaBookScreen(
    business: BusinessApi,
    route: String,
    preselectedServiceId: String? = null,
    moving: BipeBooking? = null,
    quoteAccept: (suspend (serviceId: String, agendaId: String, start: String) -> Unit)? = null,
    onClose: (Boolean) -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(route, moving?.id) { AgendaBookModel(context, business, route, scope, moving, quoteAccept) }
    LaunchedEffect(model) { model.start(preselectedServiceId) }
    BackHandler { model.back { onClose(false) } }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_BOOK.id)) {
        GroupTopBar(
            stringResource(if (moving != null) R.string.bipe_agenda_reschedule else R.string.bipe_agenda_book),
            onBack = { model.back { onClose(false) } },
            backIcon = if (model.step == AgendaBookModel.Step.SERVICE) PhosphorIcon.X else PhosphorIcon.CARET_LEFT,
        )
        when {
            model.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            model.loadFailed -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(model.error ?: stringResource(R.string.bipe_agenda_load_failed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                TextButton(onClick = { model.start(preselectedServiceId) }) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            else -> when (model.step) {
                AgendaBookModel.Step.SERVICE -> ServiceStep(model)
                AgendaBookModel.Step.PERSON -> PersonStep(model)
                AgendaBookModel.Step.TIME -> TimeStep(model)
                AgendaBookModel.Step.REVIEW -> ReviewStep(model) { onClose(true) }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun StepError(model: AgendaBookModel) {
    model.error?.let { Text(it, color = BipeTheme.colors.trustDangerInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
}

@Composable
private fun ServiceStep(model: AgendaBookModel) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    StepTitle(stringResource(R.string.bipe_agenda_pick_service))
    StepError(model)
    LazyColumn(Modifier.fillMaxSize()) {
        items(model.services, key = { it.id }) { service ->
            ListItem(
                headlineContent = { Text(service.name, color = colors.inkPrimary) },
                supportingContent = {
                    Text(
                        listOfNotNull(service.durationMinutes?.let { BusinessText.duration(context, it) }, BusinessText.price(context, service.price))
                            .joinToString(" · "),
                        color = colors.inkSecondary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
                modifier = Modifier.clickable(enabled = !model.working) { model.pickService(service) },
            )
            HorizontalDivider(color = colors.lineSubtle)
        }
    }
}

@Composable
private fun PersonStep(model: AgendaBookModel) {
    val colors = BipeTheme.colors
    StepTitle(stringResource(R.string.bipe_agenda_pick_person))
    StepError(model)
    // Qualquer pessoa first when there are two or more.
    val choices = listOf(ANY_AGENDA to stringResource(R.string.bipe_agenda_anyone)) + model.agendas.map { it.id to it.name }
    LazyColumn(Modifier.fillMaxSize()) {
        items(choices, key = { it.first }) { (id, name) ->
            ListItem(
                headlineContent = { Text(name, color = colors.inkPrimary) },
                colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
                modifier = Modifier.clickable { model.pickAgenda(id) },
            )
            HorizontalDivider(color = colors.lineSubtle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeStep(model: AgendaBookModel) {
    val colors = BipeTheme.colors
    val week = model.week
    StepTitle(stringResource(R.string.bipe_agenda_pick_time))
    StepError(model)
    // The week strip: seven days in the business time zone; a day with no free time is greyed out.
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The arrows read as the first day of the week they show.
        val previous = BusinessText.shortDate(model.weekStart.minusDays(7))
        TextButton(
            onClick = { model.showWeek(model.weekStart.minusDays(7)) },
            enabled = model.weekStart.isAfter(model.today) && !model.weekLoading,
            modifier = Modifier.semantics { contentDescription = previous },
        ) {
            Text("‹")
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(week?.days.orEmpty(), key = { it.date }) { day ->
                val local = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@items
                FilterChip(
                    selected = model.date == day.date,
                    onClick = { model.date = day.date },
                    enabled = day.slots.isNotEmpty(),
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(DateTimeFormatter.ofPattern("EEE").format(local), style = MaterialTheme.typography.labelSmall)
                            Text(local.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    },
                )
            }
        }
        val next = BusinessText.shortDate(model.weekStart.plusDays(7))
        TextButton(
            onClick = { model.showWeek(model.weekStart.plusDays(7)) },
            enabled = !model.weekLoading,
            modifier = Modifier.semantics { contentDescription = next },
        ) {
            Text("›")
        }
    }
    val selected = week?.days?.firstOrNull { it.date == model.date }
    selected?.let { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let {
            Text(BusinessText.date(it), style = MaterialTheme.typography.titleSmall, color = colors.inkSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            model.weekLoading || model.working -> CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.align(Alignment.Center))
            selected == null || selected.slots.isEmpty() -> Text(
                stringResource(R.string.bipe_agenda_no_slots),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> FlowRow(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Times show in the phone's time zone.
                selected.slots.forEach { slot ->
                    OutlinedButton(onClick = { model.pickTime(slot) }) { Text(BusinessText.time(Instant.parse(slot.start))) }
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(model: AgendaBookModel, onDone: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val service = model.service ?: return
    val slot = model.slot ?: return
    val start = Instant.parse(slot.start)
    val agendaName = model.agendas.firstOrNull { it.id == model.agendaId }?.name
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_agenda_review_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary,
            modifier = Modifier.padding(vertical = 8.dp))
        Text(service.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        Text(
            listOfNotNull(service.durationMinutes?.let { BusinessText.duration(context, it) }, BusinessText.price(context, service.price)).joinToString(" · "),
            color = colors.inkSecondary,
        )
        agendaName?.let { Text(stringResource(R.string.bipe_agenda_with, it), color = colors.inkPrimary) }
        Text(BusinessText.date(start) + " · " + BusinessText.time(start), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_agenda_cancel_policy, BusinessText.span(DEFAULT_CANCEL_WINDOW_MINUTES)), style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary)
        model.hold?.holdExpiresAt?.let { HoldCountdown(Instant.parse(it)) }
        StepError(model)
        Button(onClick = { model.confirm(onDone) }, enabled = !model.working, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (model.working) CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
            else Text(
                stringResource(
                    when {
                        model.quoteAccept != null -> R.string.bipe_quote_accept_booking
                        model.moving != null -> R.string.bipe_agenda_reschedule
                        else -> R.string.bipe_agenda_confirm
                    },
                ),
            )
        }
    }
}

/** The hold's minutes left, "4:59". A countdown on the phone only; it calls nothing. */
@Composable
private fun HoldCountdown(expires: Instant) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expires) {
        while (now < expires.toEpochMilli()) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = Duration.ofMillis((expires.toEpochMilli() - now).coerceAtLeast(0))
    val minutesLeft = String.format(Locale.ROOT, "%d:%02d", left.toMinutes(), left.seconds % 60)
    Text(stringResource(R.string.bipe_agenda_hold, minutesLeft), style = MaterialTheme.typography.bodyMedium, color = BipeTheme.colors.trustCautionInk)
}
