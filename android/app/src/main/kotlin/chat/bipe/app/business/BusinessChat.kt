// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAgendaState
import chat.bipe.shared.api.BipeBooking
import chat.bipe.shared.api.BipeBookingStatus
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.cancelBooking
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.myBookings
import chat.bipe.shared.api.publicAgendas
import chat.bipe.shared.api.publicCatalog
import chat.bipe.shared.api.publicServices
import chat.bipe.shared.api.resolveHandles
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Type Booking of the business specs: the API booking with the names to show. [business] is the route segment of the
 * customer routes, the business @handle or its Matrix ID.
 */
data class BookingView(
    val booking: BipeBooking,
    val business: String,
    val businessName: String,
    val serviceName: String,
    val agendaName: String?,
    /** Owner mode: the signed-in customer's name, or the typed name of an encaixe. */
    val customerName: String?,
) {
    val start: Instant get() = Instant.parse(booking.start)
    val end: Instant get() = Instant.parse(booking.end)
    val status: BipeBookingStatus get() = booking.bookingStatus

    /** Spec agenda-booking-card canCancel: held, requested and confirmed before the end. */
    val canCancel: Boolean get() = status.isOpen && end.isAfter(Instant.now())
}

/**
 * Spec business-chat-bar for one DM or profile: the tools a company turned on (Agendar, Ver catálogo) and the open
 * bookings with it. On a company account the same cards show for the customer of the DM (owner mode). Nothing comes
 * from the room: everything is from bipe-api (ADR 0102).
 */
class BusinessChatModel(val business: BusinessApi, private val media: MediaLoader, val peerUserId: String) {
    data class State(
        val isCompany: Boolean = false,
        val handle: String? = null,
        val canBook: Boolean = false,
        val hasCatalog: Boolean = false,
        /** IDs of the services the customer can book, for catalog-view's Agendar. */
        val bookableServices: Set<String> = emptySet(),
        val bookings: List<BookingView> = emptyList(),
        val ownerMode: Boolean = false,
        val businessName: String = "",
    ) {
        /** The customer routes take the handle, else the Matrix ID. */
        fun route(peerUserId: String): String = handle ?: peerUserId
    }

    var state by mutableStateOf(State())
        private set

    /** Action load. A failure hides a tool, never the chat. */
    suspend fun load() {
        try {
            val me = business.session.userId
            if (media.isCompany(me)) loadOwner() else if (media.isCompany(peerUserId)) loadCustomer() else state = State()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep what shows; the next open or return to the front tries again.
        }
    }

    private suspend fun loadCustomer() {
        val handle = runCatching { business.withToken { resolveHandles(listOf(peerUserId), it) }[peerUserId] }.getOrNull()
        val route = handle ?: peerUserId
        val name = business.displayName(peerUserId)
        val services = runCatching { business.api.publicServices(route) }.getOrNull().orEmpty()
        val catalog = runCatching { business.api.publicCatalog(route) }.getOrNull().orEmpty()
        val open = runCatching { business.withToken { myBookings(it) } }.getOrNull().orEmpty()
            .filter { it.businessId == peerUserId && it.bookingStatus.isOpen }
        val agendas = if (open.isEmpty()) emptyMap() else
            runCatching { business.api.publicAgendas(route, null) }.getOrNull().orEmpty().associate { it.id to it.name }
        val names = (catalog + services).associate { it.id to it.name }
        state = State(
            isCompany = true,
            handle = handle,
            canBook = services.isNotEmpty(),
            hasCatalog = catalog.isNotEmpty(),
            bookableServices = services.map { it.id }.toSet(),
            bookings = open.map { BookingView(it, route, name, names[it.serviceId].orEmpty(), agendas[it.agendaId], null) },
            businessName = name,
        )
    }

    private suspend fun loadOwner() {
        val agenda = runCatching { business.withToken { myAgenda(it) } }.getOrNull()
        if (agenda == null) {
            state = State(ownerMode = true)
            return
        }
        val open = business.withToken { myBookings(it) }.filter { it.customer.userId == peerUserId && it.bookingStatus.isOpen }
        val customer = business.displayName(peerUserId)
        state = State(ownerMode = true, bookings = open.map { ownerView(it, agenda, business.session.userId, customer) })
    }

    companion object {
        /** A booking of the owner's own agenda, with the names from AgendaState. */
        fun ownerView(booking: BipeBooking, agenda: BipeAgendaState, businessId: String, customerName: String?): BookingView = BookingView(
            booking = booking,
            business = businessId,
            businessName = "",
            serviceName = agenda.services.firstOrNull { it.id == booking.serviceId }?.name.orEmpty(),
            agendaName = agenda.agendas.firstOrNull { it.id == booking.agendaId }?.name,
            customerName = customerName ?: booking.customer.name,
        )
    }
}

/** What the business tools of a chat or profile have open. */
internal sealed interface BusinessSheet {
    data class Book(val serviceId: String? = null, val moving: BookingView? = null) : BusinessSheet
    data object Catalog : BusinessSheet
    data class Owner(val booking: BookingView) : BusinessSheet
}

/**
 * The business tools of one DM: [MenuItems] go in the chat header menu, [Cards] above the timeline, [Sheets] once at the
 * end of the screen.
 */
class BusinessChatTools(session: AccountSession, media: MediaLoader, peerUserId: String) {
    val model = BusinessChatModel(BusinessApi(session), media, peerUserId)
    private var sheet by mutableStateOf<BusinessSheet?>(null)

    val hasMenuItems: Boolean get() = !model.state.ownerMode && (model.state.canBook || model.state.hasCatalog)

    /** The chat header menu items (one per tool). */
    @Composable
    fun MenuItems(dismiss: () -> Unit) {
        val state = model.state
        if (state.ownerMode) return
        if (state.canBook) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_agenda_book)) }, onClick = {
                dismiss()
                sheet = BusinessSheet.Book()
            })
        }
        if (state.hasCatalog) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_catalog_open)) }, onClick = {
                dismiss()
                sheet = BusinessSheet.Catalog
            })
        }
    }

    /** Spec business-chat-bar: the buttons under the name on a company's person-profile (ADR 0103: Agendar). */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProfileButtons() {
        val state = model.state
        if (state.ownerMode || (!state.canBook && !state.hasCatalog)) return
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag(BipeScreen.BUSINESS_CHAT_BAR.id),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (state.canBook) Button(onClick = { sheet = BusinessSheet.Book() }) { Text(stringResource(R.string.bipe_agenda_book)) }
            if (state.hasCatalog) FilledTonalButton(onClick = { sheet = BusinessSheet.Catalog }) { Text(stringResource(R.string.bipe_catalog_open)) }
        }
    }

    /** The booking cards pinned above the timeline (agenda-booking-card). */
    @Composable
    fun Cards() {
        val state = model.state
        if (state.bookings.isEmpty()) return
        val label = stringResource(R.string.bipe_business_tools_a11y)
        val scope = rememberCoroutineScope()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(BipeScreen.BUSINESS_CHAT_BAR.id)
                .semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.bookings.forEach { view ->
                BookingCard(
                    view = view,
                    ownerMode = state.ownerMode,
                    business = model.business,
                    onReschedule = { sheet = BusinessSheet.Book(moving = view) },
                    onOpenOwner = { sheet = BusinessSheet.Owner(view) },
                    onChanged = { scope.launch { model.load() } },
                )
            }
        }
    }

    /** The flows the tools open: agenda-book, catalog-view and agenda-owner-booking. */
    @Composable
    fun Sheets() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val state = model.state
        val close: (Boolean) -> Unit = { changed ->
            sheet = null
            if (changed) scope.launch { model.load() }
        }
        when (val open = sheet) {
            null -> Unit
            is BusinessSheet.Book -> BusinessFullScreen(onDismiss = { close(false) }) {
                AgendaBookScreen(
                    business = model.business,
                    route = open.moving?.business ?: state.route(model.peerUserId),
                    preselectedServiceId = open.serviceId ?: open.moving?.booking?.serviceId,
                    moving = open.moving?.booking,
                    onClose = { booked ->
                        if (booked) Toast.makeText(context, context.getString(R.string.bipe_agenda_booked), Toast.LENGTH_LONG).show()
                        close(booked)
                    },
                )
            }
            BusinessSheet.Catalog -> BusinessFullScreen(onDismiss = { close(false) }) {
                CatalogViewScreen(
                    business = model.business,
                    route = state.route(model.peerUserId),
                    bookableServices = state.bookableServices,
                    onBook = { serviceId -> sheet = BusinessSheet.Book(serviceId = serviceId) },
                    onClose = { close(false) },
                )
            }
            // The chat with this customer is already open: openChat only closes the sheet.
            is BusinessSheet.Owner -> AgendaOwnerBookingSheet(
                business = model.business,
                booking = open.booking,
                onOpenChat = { close(false) },
                onClose = { changed -> close(changed) },
            )
        }
    }
}

/**
 * Spec agenda-booking-card: service, person, date, time and status, with Adicionar ao calendário, Remarcar and
 * Cancelar. In owner mode it shows the customer and a tap opens agenda-owner-booking. iOS draws it the same way.
 */
@Composable
fun BookingCard(
    view: BookingView,
    ownerMode: Boolean,
    business: BusinessApi,
    onReschedule: () -> Unit,
    onOpenOwner: () -> Unit,
    onChanged: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val click = when {
        ownerMode -> onOpenOwner
        else -> onOpen
    }
    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag(BipeScreen.AGENDA_BOOKING_CARD.id)
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.CLOCK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.bipe_agenda_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentInk,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(BusinessText.status(view.status)), style = MaterialTheme.typography.labelMedium, color = statusColor(view.status))
            }
            Text(view.serviceName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            if (ownerMode) view.customerName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary) }
            view.agendaName?.let { Text(stringResource(R.string.bipe_agenda_with, it), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
            Text(
                BusinessText.date(view.start) + " · " + BusinessText.time(view.start) + " – " + BusinessText.time(view.end),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
            if (!ownerMode && view.status.isOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        BookingCalendar.open(
                            context, view.booking.id, view.start, view.end,
                            context.getString(R.string.bipe_agenda_calendar_title, view.serviceName, view.businessName),
                        )
                    }) { Text(stringResource(R.string.bipe_agenda_add_to_calendar)) }
                    TextButton(onClick = onReschedule, enabled = !working && view.canCancel) { Text(stringResource(R.string.bipe_agenda_reschedule)) }
                    if (view.canCancel) {
                        TextButton(onClick = { confirmCancel = true }, enabled = !working) {
                            Text(stringResource(R.string.bipe_agenda_cancel), color = colors.trustDangerInk)
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        CancelBookingDialog(
            onKeep = { confirmCancel = false },
            onCancel = {
                confirmCancel = false
                working = true
                error = null
                scope.launch {
                    runCatching { business.withToken { cancelBooking(view.business, view.booking.id, it) } }
                        .onSuccess { onChanged() }
                        .onFailure { error = BusinessText.error(context, it) }
                    working = false
                }
            },
        )
    }
}

/** bipe_agenda_cancel_confirm with its body; Manter agendamento keeps it. */
@Composable
fun CancelBookingDialog(onKeep: () -> Unit, onCancel: () -> Unit) {
    val colors = BipeTheme.colors
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.bipe_agenda_cancel_confirm)) },
        text = { Text(stringResource(R.string.bipe_agenda_cancel_confirm_body), color = colors.inkSecondary) },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_agenda_cancel), color = colors.trustDangerInk) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.bipe_agenda_keep)) } },
        containerColor = colors.surfaceRaised,
    )
}

@Composable
fun statusColor(status: BipeBookingStatus) = when (status) {
    BipeBookingStatus.CONFIRMED, BipeBookingStatus.DONE -> BipeTheme.colors.trustVerifiedInk
    BipeBookingStatus.HELD, BipeBookingStatus.REQUESTED -> BipeTheme.colors.trustCautionInk
    BipeBookingStatus.CANCELLED, BipeBookingStatus.NO_SHOW -> BipeTheme.colors.inkSecondary
}

/** Loads the tools of a chat when it opens and when the app comes back to the front (spec business-chat-bar load). */
@Composable
fun rememberBusinessChatTools(session: AccountSession, media: MediaLoader, peerUserId: String?): BusinessChatTools? {
    val tools = remember(session, peerUserId) { peerUserId?.let { BusinessChatTools(session, media, it) } }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(tools, foreground) { tools?.model?.load() }
    return tools
}

/** Spec business-chat-bar on person-profile of a company account: Agendar and Ver catálogo under the name. */
@Composable
fun BusinessProfileButtons(session: AccountSession, media: MediaLoader, userId: String) {
    val tools = rememberBusinessChatTools(session, media, userId) ?: return
    tools.ProfileButtons()
    tools.Sheets()
}
