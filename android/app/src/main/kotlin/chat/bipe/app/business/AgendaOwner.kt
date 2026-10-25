// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.NativeDmSelection
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAgendaState
import chat.bipe.shared.api.BipeBookingCustomer
import chat.bipe.shared.api.BipeBookingStatus
import chat.bipe.shared.api.BipeOwnerBookingAction
import chat.bipe.shared.api.blockAgendas
import chat.bipe.shared.api.closeAgendaDay
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.ownerBook
import chat.bipe.shared.api.ownerBookingAction
import chat.bipe.shared.api.ownerBookings
import chat.bipe.shared.api.ownerMoveBooking
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.messageEventContentNew

/** Days the owner's date strip and pickers reach back and ahead. */
private const val DAYS_BACK = 7L
private const val DAYS_AHEAD = 90L

/** Spec agenda-owner: the company account's Agenda tab. */
class AgendaOwnerModel(private val context: Context, val business: BusinessApi, private val scope: CoroutineScope) {
    enum class Phase { LOADING, OFF, ON, FAILED }

    var phase by mutableStateOf(Phase.LOADING)
    var agenda by mutableStateOf<BipeAgendaState?>(null)
    var date by mutableStateOf(LocalDate.now())
    /** An agenda ID, or `null` for Todos. */
    var filter by mutableStateOf<String?>(null)
    var bookings by mutableStateOf<List<BookingView>>(emptyList())
    var dayLoading by mutableStateOf(false)
    var dayFailed by mutableStateOf(false)
    private var dayJob: Job? = null

    /** The business time zone (AgendaState.time_zone). */
    val zone: ZoneId get() = agenda?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val today: LocalDate get() = LocalDate.now(zone)

    /** The agendas an action on screen applies to: the filtered one, or all for Todos. */
    val agendaIds: List<String> get() = filter?.let { listOf(it) } ?: agenda?.agendas.orEmpty().map { it.id }

    /** Action refresh: the state, then the day. */
    fun refresh() {
        scope.launch {
            if (agenda == null) phase = Phase.LOADING
            try {
                val state = business.withToken { myAgenda(it) }
                val firstLoad = agenda == null
                agenda = state
                phase = if (state == null) Phase.OFF else Phase.ON
                if (state != null) {
                    if (firstLoad) date = today
                    if (filter != null && state.agendas.none { it.id == filter }) filter = null
                    loadDay()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (agenda == null) phase = Phase.FAILED
            }
        }
    }

    fun pickDate(day: LocalDate) {
        date = day
        loadDay()
    }

    fun pickAgenda(id: String?) {
        filter = id
        loadDay()
    }

    fun loadDay() {
        val state = agenda ?: return
        val day = date.toString()
        dayLoading = true
        dayFailed = false
        dayJob?.cancel()
        dayJob = scope.launch {
            try {
                val list = business.withToken { ownerBookings(day, day, filter, it) }
                bookings = list.sortedBy { it.start }.map { booking ->
                    val name = booking.customer.userId?.let { business.displayName(it) } ?: booking.customer.name
                    BusinessChatModel.ownerView(booking, state, business.session.userId, name)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                dayFailed = true
            } finally {
                dayLoading = false
            }
        }
    }

    /** Action block: a start and an end on the day on screen, for the agenda on screen or all. */
    suspend fun block(start: LocalTime, end: LocalTime): String? = try {
        val from = ZonedDateTime.of(date, start, zone).toInstant()
        val to = ZonedDateTime.of(date, end, zone).toInstant()
        business.withToken { blockAgendas(agendaIds, BusinessText.utc(from), BusinessText.utc(to), it) }
        loadDay()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (failure: Exception) {
        BusinessText.error(context, failure)
    }

    /**
     * Action closeToday: closes today for the agenda on screen or all, and returns the signed-in customers of today's
     * open bookings, for the message. `null` on failure.
     */
    suspend fun closeToday(): List<String>? = try {
        val day = today.toString()
        business.withToken { closeAgendaDay(agendaIds, day, it) }
        val open = business.withToken { ownerBookings(day, day, filter, it) }
        if (date == today) loadDay()
        open.filter { it.bookingStatus.isOpen }.mapNotNull { it.customer.userId }.distinct()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Sends the owner's text into the DM with each customer, from this phone through the encrypted chat (ADR 0102).
     * A customer without a DM is skipped. Returns how many got it.
     */
    suspend fun sendToCustomers(customers: List<String>, text: String): Int {
        var sent = 0
        for (userId in customers) {
            try {
                val roomId = NativeDmSelection.roomId(userId, business.session) ?: continue
                val room = business.session.client.getRoom(roomId) ?: continue
                room.timeline().send(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))))
                sent++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The others still get it.
            }
        }
        return sent
    }
}

/** What the Agenda tab has open. */
private sealed interface OwnerSheet {
    data object Setup : OwnerSheet
    data object Settings : OwnerSheet
    data class Booking(val booking: BookingView) : OwnerSheet
    data object WalkIn : OwnerSheet
    data object Block : OwnerSheet
    data object CloseToday : OwnerSheet
    data class CloseMessage(val customers: List<String>) : OwnerSheet
}

/**
 * Spec agenda-owner: before activation an invitation to Ativar agenda; after it, the day's bookings for one person or
 * Todos with a now-line on today, Encaixe, Bloquear horário, Fechar hoje and Ajustes. [onOpenChat] takes a room ID.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgendaOwnerScreen(session: AccountSession, chatList: ChatListModel, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val business = remember(session) { BusinessApi(session) }
    val model = remember(session) { AgendaOwnerModel(context.applicationContext, business, scope) }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(model, foreground) { model.refresh() }
    var sheet by remember { mutableStateOf<OwnerSheet?>(null) }
    val chats by chatList.chats.collectAsState()
    val openCustomerChat: (String) -> Unit = { userId ->
        scope.launch { NativeDmSelection.roomId(userId, session)?.let(onOpenChat) }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_OWNER.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.bipe_tab_agenda), style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
                    model.agenda?.let {
                        Text(
                            stringResource(if (it.published) R.string.bipe_agenda_published else R.string.bipe_agenda_not_published),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.published) colors.trustVerifiedInk else colors.trustCautionInk,
                        )
                    }
                }
                if (model.phase == AgendaOwnerModel.Phase.ON) {
                    IconButton(onClick = { sheet = OwnerSheet.Settings }) {
                        Icon(painterResource(PhosphorIcon.GEAR.drawable), contentDescription = stringResource(R.string.bipe_agenda_settings_title), tint = colors.glyphPrimary)
                    }
                }
            }
            when (model.phase) {
                AgendaOwnerModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
                AgendaOwnerModel.Phase.FAILED -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(stringResource(R.string.bipe_agenda_load_failed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    TextButton(onClick = model::refresh) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                AgendaOwnerModel.Phase.OFF -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Icon(painterResource(PhosphorIcon.CLOCK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.bipe_agenda_tab_off_body), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    Button(onClick = { sheet = OwnerSheet.Setup }) { Text(stringResource(R.string.bipe_agenda_activate)) }
                }
                AgendaOwnerModel.Phase.ON -> {
                    DateStrip(model)
                    val agenda = model.agenda
                    if (agenda != null && agenda.agendas.size > 1) {
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                            item(key = "all") {
                                FilterChip(selected = model.filter == null, onClick = { model.pickAgenda(null) }, label = { Text(stringResource(R.string.bipe_agenda_all_people)) })
                            }
                            items(agenda.agendas, key = { it.id }) { item ->
                                FilterChip(selected = model.filter == item.id, onClick = { model.pickAgenda(item.id) }, label = { Text(item.name) })
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { sheet = OwnerSheet.WalkIn }, label = { Text(stringResource(R.string.bipe_agenda_walk_in)) })
                        AssistChip(onClick = { sheet = OwnerSheet.Block }, label = { Text(stringResource(R.string.bipe_agenda_block)) })
                        AssistChip(onClick = { sheet = OwnerSheet.CloseToday }, label = { Text(stringResource(R.string.bipe_agenda_close_today)) })
                    }
                    DayList(model) { sheet = OwnerSheet.Booking(it) }
                }
            }
        }
    }

    val agenda = model.agenda
    when (val open = sheet) {
        null -> Unit
        OwnerSheet.Setup, OwnerSheet.Settings -> BusinessFullScreen(onDismiss = { sheet = null; model.refresh() }) {
            if (open == OwnerSheet.Setup || agenda == null) {
                AgendaSetupScreen(business, startAtHours = agenda != null, onClose = { sheet = null; model.refresh() })
            } else {
                AgendaSettingsScreen(business, agenda, onClose = { sheet = null; model.refresh() }, onEditSetup = { sheet = OwnerSheet.Setup })
            }
        }
        is OwnerSheet.Booking -> AgendaOwnerBookingSheet(
            business = business,
            booking = open.booking,
            agenda = agenda,
            onOpenChat = { userId -> sheet = null; openCustomerChat(userId) },
            onClose = { changed -> sheet = null; if (changed) model.loadDay() },
        )
        OwnerSheet.WalkIn -> AgendaOwnerBookingSheet(
            business = business,
            booking = null,
            agenda = agenda,
            date = model.date,
            customers = chats.filter { it.isDm && it.peerUserId != null }.map { it.peerUserId!! to it.name },
            onOpenChat = { userId -> sheet = null; openCustomerChat(userId) },
            onClose = { changed -> sheet = null; if (changed) model.loadDay() },
        )
        OwnerSheet.Block -> BlockDialog(model, onClose = { sheet = null })
        OwnerSheet.CloseToday -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text(stringResource(R.string.bipe_agenda_close_today)) },
            text = { Text(stringResource(R.string.bipe_agenda_close_today_body), color = colors.inkSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val customers = model.closeToday()
                        sheet = when {
                            customers == null -> {
                                Toast.makeText(context, context.getString(R.string.bipe_agenda_error_save), Toast.LENGTH_SHORT).show()
                                null
                            }
                            customers.isEmpty() -> null
                            else -> OwnerSheet.CloseMessage(customers)
                        }
                    }
                }) { Text(stringResource(R.string.bipe_agenda_close_today), color = colors.trustDangerInk) }
            },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
            containerColor = colors.surfaceRaised,
        )
        is OwnerSheet.CloseMessage -> CloseMessageDialog(model, open.customers, onClose = { sheet = null })
    }
}

/** The date strip: days around today in the business time zone, today first in view. */
@Composable
private fun DateStrip(model: AgendaOwnerModel) {
    val today = model.today
    val days = remember(today) { (-DAYS_BACK..DAYS_AHEAD).map { today.plusDays(it) } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = DAYS_BACK.toInt())
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { model.pickDate(today) }) { Text(stringResource(R.string.bipe_agenda_today)) }
        LazyRow(state = state, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days, key = { it.toString() }) { day ->
                FilterChip(
                    selected = day == model.date,
                    onClick = { model.pickDate(day) },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(java.time.format.DateTimeFormatter.ofPattern("EEE").format(day), style = MaterialTheme.typography.labelSmall)
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    },
                )
            }
        }
    }
    Text(
        BusinessText.date(model.date),
        style = MaterialTheme.typography.titleSmall,
        color = BipeTheme.colors.inkSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The day's bookings by time; cancelled and no-show ones dimmed; on today a now-line between past and next. */
@Composable
private fun DayList(model: AgendaOwnerModel, onOpen: (BookingView) -> Unit) {
    val colors = BipeTheme.colors
    Box(Modifier.fillMaxSize()) {
        when {
            model.dayLoading && model.bookings.isEmpty() -> CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.align(Alignment.Center))
            model.dayFailed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bipe_agenda_load_failed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                TextButton(onClick = model::loadDay) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            model.bookings.isEmpty() -> Text(
                stringResource(R.string.bipe_agenda_empty_day),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> {
                val now = Instant.now()
                val nowIndex = if (model.date == model.today) model.bookings.indexOfFirst { it.start.isAfter(now) }.let { if (it < 0) model.bookings.size else it } else -1
                LazyColumn(Modifier.fillMaxSize()) {
                    model.bookings.forEachIndexed { index, view ->
                        if (index == nowIndex) item(key = "now") { NowLine() }
                        item(key = view.booking.id) { OwnerBookingRow(view, showsAgenda = model.filter == null) { onOpen(view) } }
                    }
                    if (nowIndex == model.bookings.size) item(key = "now") { NowLine() }
                }
            }
        }
    }
}

@Composable
private fun NowLine() {
    val colors = BipeTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bipe_agenda_now), style = MaterialTheme.typography.labelMedium, color = colors.trustDangerInk)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = colors.trustDangerInk, thickness = 2.dp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OwnerBookingRow(view: BookingView, showsAgenda: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val ended = view.status == BipeBookingStatus.CANCELLED || view.status == BipeBookingStatus.NO_SHOW
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (ended) 0.5f else 1f),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(72.dp)) {
            Text(BusinessText.time(view.start), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
            Text(BusinessText.time(view.end), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(view.customerName.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
            Text(view.serviceName, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            if (showsAgenda) view.agendaName?.let { Text(stringResource(R.string.bipe_agenda_with, it), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
            Text(stringResource(BusinessText.status(view.status)), style = MaterialTheme.typography.labelSmall, color = statusColor(view.status))
        }
    }
    HorizontalDivider(color = colors.lineSubtle)
}

/** Action block: a start and an end on the day on screen. */
@Composable
private fun BlockDialog(model: AgendaOwnerModel, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val times = remember(model.agenda) { dayTimes(model.agenda?.policy?.slotStepMinutes ?: 15) }
    var start by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(13, 0)) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(stringResource(R.string.bipe_agenda_block)) },
        text = {
            Column {
                Text(BusinessText.date(model.date), color = colors.inkSecondary)
                PickerField(stringResource(R.string.bipe_agenda_time), BusinessText.clock(start), times.map { it to BusinessText.clock(it) }) { start = it }
                PickerField(stringResource(R.string.bipe_agenda_time), BusinessText.clock(end), times.filter { it.isAfter(start) }.map { it to BusinessText.clock(it) }) { end = it }
                error?.let { Text(it, color = colors.trustDangerInk) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && end.isAfter(start), onClick = {
                working = true
                scope.launch {
                    error = model.block(start, end)
                    working = false
                    if (error == null) onClose()
                }
            }) { Text(stringResource(R.string.bipe_app_save)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** After Fechar hoje: the owner edits the message, then it goes to each signed-in customer of today. */
@Composable
private fun CloseMessageDialog(model: AgendaOwnerModel, customers: List<String>, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(context.getString(R.string.bipe_agenda_close_today_message)) }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onClose() },
        title = { Text(stringResource(R.string.bipe_agenda_close_today)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !sending, minLines = 3, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = !sending && text.isNotBlank(), onClick = {
                sending = true
                scope.launch {
                    val sent = model.sendToCustomers(customers, text.trim())
                    Toast.makeText(context, context.getString(R.string.bipe_agenda_close_today_sent, sent.toString()), Toast.LENGTH_SHORT).show()
                    sending = false
                    onClose()
                }
            }) { Text(stringResource(R.string.bipe_agenda_close_today_send)) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/**
 * Spec agenda-owner-booking: one booking on the company side ([booking]), or a new encaixe (`null`). [customers] are the
 * company's DMs (Matrix ID and name) for an encaixe with a signed-in customer. [onOpenChat] takes the customer's Matrix ID.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgendaOwnerBookingSheet(
    business: BusinessApi,
    booking: BookingView?,
    agenda: BipeAgendaState? = null,
    date: LocalDate? = null,
    customers: List<Pair<String, String>> = emptyList(),
    onOpenChat: (String) -> Unit,
    onClose: (Boolean) -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(agenda) }
    LaunchedEffect(Unit) { if (state == null) state = runCatching { business.withToken { myAgenda(it) } }.getOrNull() }
    val zone = state?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    var editing by remember { mutableStateOf(booking == null) }
    var serviceId by remember { mutableStateOf(booking?.booking?.serviceId) }
    var agendaId by remember { mutableStateOf(booking?.booking?.agendaId) }
    var day by remember { mutableStateOf(booking?.start?.atZone(zone)?.toLocalDate() ?: date ?: LocalDate.now(zone)) }
    var time by remember { mutableStateOf(booking?.start?.atZone(zone)?.toLocalTime() ?: LocalTime.of(9, 0)) }
    var fromChats by remember { mutableStateOf(customers.isNotEmpty()) }
    var customerId by remember { mutableStateOf<String?>(null) }
    var customerName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }

    fun perform(call: suspend () -> Unit) {
        saving = true
        error = null
        scope.launch {
            try {
                call()
                onClose(true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BusinessText.error(context, failure)
            }
            saving = false
        }
    }

    BusinessFullScreen(onDismiss = { if (!saving) onClose(false) }) {
        Column(Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_OWNER_BOOKING.id)) {
            GroupTopBar(
                if (booking == null) stringResource(R.string.bipe_agenda_walk_in) else booking.serviceName,
                onBack = { onClose(false) }.takeUnless { saving },
                backIcon = PhosphorIcon.X,
            )
            val current = state
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
                return@Column
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (booking != null) {
                    Text(stringResource(BusinessText.status(booking.status)), style = MaterialTheme.typography.labelLarge, color = statusColor(booking.status))
                    Detail(stringResource(R.string.bipe_agenda_customer), booking.customerName.orEmpty())
                    Detail(stringResource(R.string.bipe_agenda_service), booking.serviceName)
                    booking.agendaName?.let { Detail(stringResource(R.string.bipe_agenda_person), it) }
                    Detail(stringResource(R.string.bipe_agenda_date), BusinessText.date(booking.start))
                    Detail(stringResource(R.string.bipe_agenda_time), BusinessText.time(booking.start) + " – " + BusinessText.time(booking.end))
                }
                if (editing) {
                    val services = current.services
                    val service = services.firstOrNull { it.id == serviceId }
                    if (booking == null) {
                        PickerField(stringResource(R.string.bipe_agenda_service), service?.name.orEmpty(), services.map { it.id to it.name }) {
                            serviceId = it
                            val people = services.firstOrNull { item -> item.id == it }?.agendaIds.orEmpty()
                            if (agendaId !in people) agendaId = people.firstOrNull()
                        }
                    }
                    val people = current.agendas.filter { service?.agendaIds?.contains(it.id) ?: true }
                    PickerField(stringResource(R.string.bipe_agenda_person), current.agendas.firstOrNull { it.id == agendaId }?.name.orEmpty(),
                        people.map { it.id to it.name }) { agendaId = it }
                    val today = LocalDate.now(zone)
                    PickerField(stringResource(R.string.bipe_agenda_date), BusinessText.shortDate(day),
                        (0..DAYS_AHEAD).map { today.plusDays(it) }.map { it to BusinessText.shortDate(it) }) { day = it }
                    PickerField(stringResource(R.string.bipe_agenda_time), BusinessText.clock(time),
                        dayTimes(current.policy.slotStepMinutes).map { it to BusinessText.clock(it) }) { time = it }
                    if (booking == null) {
                        Text(stringResource(R.string.bipe_agenda_customer), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (customers.isNotEmpty()) {
                                FilterChip(selected = fromChats, onClick = { fromChats = true }, label = { Text(stringResource(R.string.bipe_agenda_customer_from_chats)) })
                            }
                            FilterChip(selected = !fromChats, onClick = { fromChats = false }, label = { Text(stringResource(R.string.bipe_agenda_customer_name)) })
                        }
                        if (fromChats) {
                            PickerField(stringResource(R.string.bipe_agenda_customer_from_chats), customers.firstOrNull { it.first == customerId }?.second.orEmpty(),
                                customers) { customerId = it }
                        } else {
                            OutlinedTextField(
                                value = customerName,
                                onValueChange = { customerName = it.take(120) },
                                label = { Text(stringResource(R.string.bipe_agenda_customer_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.bipe_agenda_walk_in_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                    }
                }
                error?.let { Text(it, color = colors.trustDangerInk) }
                Spacer(Modifier.height(8.dp))
                if (editing) {
                    val customerReady = booking != null || (if (fromChats) customerId != null else customerName.isNotBlank())
                    Button(
                        enabled = !saving && serviceId != null && agendaId != null && customerReady,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val start = BusinessText.utc(ZonedDateTime.of(day, time, zone).toInstant())
                            val agenda = agendaId
                            val service = serviceId
                            if (agenda != null && booking != null) {
                                perform { business.withToken { ownerMoveBooking(booking.booking.id, agenda, start, it) } }
                            } else if (agenda != null && service != null) {
                                val customer = if (fromChats) BipeBookingCustomer(userId = customerId) else BipeBookingCustomer(name = customerName.trim())
                                perform { business.withToken { ownerBook(service, agenda, start, customer, it) } }
                            }
                        },
                    ) { Text(stringResource(R.string.bipe_app_save)) }
                } else if (booking != null) {
                    val now = Instant.now()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (booking.status == BipeBookingStatus.REQUESTED) {
                            Button(enabled = !saving, onClick = { perform { business.withToken { ownerBookingAction(booking.booking.id, BipeOwnerBookingAction.ACCEPT, it) } } }) {
                                Text(stringResource(R.string.bipe_agenda_accept))
                            }
                            OutlinedButton(enabled = !saving, onClick = { perform { business.withToken { ownerBookingAction(booking.booking.id, BipeOwnerBookingAction.REFUSE, it) } } }) {
                                Text(stringResource(R.string.bipe_agenda_decline))
                            }
                        }
                        if (booking.status == BipeBookingStatus.REQUESTED || booking.status == BipeBookingStatus.CONFIRMED) {
                            OutlinedButton(enabled = !saving, onClick = { editing = true }) { Text(stringResource(R.string.bipe_agenda_reschedule)) }
                        }
                        // Não veio only after the start.
                        if ((booking.status == BipeBookingStatus.CONFIRMED || booking.status == BipeBookingStatus.DONE) && !booking.start.isAfter(now)) {
                            OutlinedButton(enabled = !saving, onClick = { perform { business.withToken { ownerBookingAction(booking.booking.id, BipeOwnerBookingAction.NO_SHOW, it) } } }) {
                                Text(stringResource(R.string.bipe_agenda_mark_no_show))
                            }
                        }
                        booking.booking.customer.userId?.let { userId ->
                            OutlinedButton(onClick = { onOpenChat(userId) }) { Text(stringResource(R.string.bipe_app_person_message)) }
                        }
                    }
                    if (booking.status.isOpen) {
                        TextButton(enabled = !saving, onClick = { confirmCancel = true }) {
                            Text(stringResource(R.string.bipe_agenda_cancel), color = colors.trustDangerInk)
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel && booking != null) {
        CancelBookingDialog(
            onKeep = { confirmCancel = false },
            onCancel = {
                confirmCancel = false
                perform { business.withToken { ownerBookingAction(booking.booking.id, BipeOwnerBookingAction.CANCEL, it) } }
            },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val colors = BipeTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
    }
}
