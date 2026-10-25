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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
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
import chat.bipe.shared.api.BipeOrder
import chat.bipe.shared.api.BipeQueuePosition
import chat.bipe.shared.api.BipeQuote
import chat.bipe.shared.api.BipeQuoteRequest
import chat.bipe.shared.api.acceptQuoteForBooking
import chat.bipe.shared.api.cancelBooking
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.myBookings
import chat.bipe.shared.api.myOrders
import chat.bipe.shared.api.myQueuePlace
import chat.bipe.shared.api.myQueuePlaces
import chat.bipe.shared.api.myQuotes
import chat.bipe.shared.api.ownerCatalog
import chat.bipe.shared.api.ownerQuoteRequests
import chat.bipe.shared.api.publicQueue
import chat.bipe.shared.api.publicAgendas
import chat.bipe.shared.api.publicCatalog
import chat.bipe.shared.api.publicServices
import chat.bipe.shared.api.resolveHandles
import chat.bipe.shared.generated.BipeScreen
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * Spec business-chat-bar for one DM or profile: the tools a company turned on (Agendar, Ver catálogo, Fazer pedido,
 * Pedir orçamento, Entrar na fila) and the open records with it (bookings, orders, quotes, the queue place). On a
 * company account the same cards show for the customer of the DM (owner mode). Nothing comes from the room:
 * everything is from bipe-api (ADR 0102).
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
        /** The catalogue has an active product with a fixed price and amount_cents: Fazer pedido shows. */
        val canOrder: Boolean = false,
        /** The orderable products, for order-customer. */
        val products: List<BipeCatalogItem> = emptyList(),
        /** Every catalogue name (services too), for the quote visit service. */
        val itemNames: Map<String, String> = emptyMap(),
        /** Today's queue is open: Entrar na fila shows. */
        val queueOpen: Boolean = false,
        /** Open orders with this business (owner mode: of this customer), and a customer's last ended one of the day. */
        val orders: List<BipeOrder> = emptyList(),
        /** Offered and recent quotes, newest change first. */
        val quotes: List<BipeQuote> = emptyList(),
        /** The active queue place. */
        val queuePlace: BipeQueuePosition? = null,
        /** Owner mode: this customer's quote requests with no quote yet. */
        val quoteRequests: List<BipeQuoteRequest> = emptyList(),
        /** Owner mode: the customer's display name. */
        val customerName: String = "",
    ) {
        /** The customer routes take the handle, else the Matrix ID. */
        fun route(peerUserId: String): String = handle ?: peerUserId

        val hasCards: Boolean
            get() = bookings.isNotEmpty() || orders.isNotEmpty() || quotes.isNotEmpty() || queuePlace != null || quoteRequests.isNotEmpty()
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

    /** Spec queue-customer load: the active place again, for the 30-second refresh of the queue card. */
    suspend fun refreshQueue() {
        try {
            val place = if (state.ownerMode) ownerPlace() else customerPlace()
            state = state.copy(queuePlace = place)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The card keeps the last answer.
        }
    }

    private suspend fun customerPlace(): BipeQueuePosition? =
        business.withToken { myQueuePlace(state.route(peerUserId), it) }?.takeIf { it.place.placeStatus.isActive }

    private suspend fun ownerPlace(): BipeQueuePosition? =
        business.withToken { myQueuePlaces(it) }.firstOrNull { it.place.customer.userId == peerUserId && it.place.placeStatus.isActive }

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
        val queue = runCatching { business.api.publicQueue(route) }.getOrNull()
        val orders = runCatching { business.withToken { myOrders(it) } }.getOrNull().orEmpty().filter { it.businessId == peerUserId }
        val quotes = runCatching { business.withToken { myQuotes(it) } }.getOrNull().orEmpty().filter { it.businessId == peerUserId }
        val place = runCatching { business.withToken { myQueuePlaces(it) } }.getOrNull().orEmpty()
            .firstOrNull { it.place.businessId == peerUserId && it.place.placeStatus.isActive }
        val products = catalog.filter(::isOrderable)
        state = State(
            isCompany = true,
            handle = handle,
            canBook = services.isNotEmpty(),
            hasCatalog = catalog.isNotEmpty(),
            bookableServices = services.map { it.id }.toSet(),
            bookings = open.map { BookingView(it, route, name, names[it.serviceId].orEmpty(), agendas[it.agendaId], null) },
            businessName = name,
            canOrder = products.isNotEmpty(),
            products = products,
            itemNames = names,
            queueOpen = queue?.open == true,
            orders = shownOrders(orders),
            quotes = quotes.take(MAX_QUOTE_CARDS),
            queuePlace = place,
        )
    }

    private suspend fun loadOwner() {
        val me = business.session.userId
        val customer = business.displayName(peerUserId)
        val agenda = runCatching { business.withToken { myAgenda(it) } }.getOrNull()
        val bookings = if (agenda == null) emptyList() else runCatching { business.withToken { myBookings(it) } }.getOrNull().orEmpty()
            .filter { it.customer.userId == peerUserId && it.bookingStatus.isOpen }
            .map { ownerView(it, agenda, me, customer) }
        val catalog = runCatching { business.withToken { ownerCatalog(it) } }.getOrNull().orEmpty()
        val orders = runCatching { business.withToken { myOrders(it) } }.getOrNull().orEmpty()
            .filter { it.customer.userId == peerUserId && it.orderStatus.isOpen }
        val quotes = runCatching { business.withToken { myQuotes(it) } }.getOrNull().orEmpty().filter { it.customerId == peerUserId }
        val requests = runCatching { business.withToken { ownerQuoteRequests(it) } }.getOrNull().orEmpty().filter { it.customerId == peerUserId }
        val place = runCatching { ownerPlace() }.getOrNull()
        state = State(
            ownerMode = true,
            bookings = bookings,
            itemNames = catalog.associate { it.id to it.name },
            orders = orders,
            quotes = quotes.take(MAX_QUOTE_CARDS),
            queuePlace = place,
            quoteRequests = requests,
            customerName = customer,
        )
    }

    companion object {
        /** Quote cards pinned at most (the newest changes). */
        private const val MAX_QUOTE_CARDS = 3

        /** A customer's ended order stays pinned this long after it was made, so a refusal and its reason show. */
        private const val ENDED_ORDER_HOURS = 24L

        /** Open orders; with none, the newest order of the last day, so its status (and a refusal's reason) shows. */
        private fun shownOrders(orders: List<BipeOrder>): List<BipeOrder> {
            val open = orders.filter { it.orderStatus.isOpen }
            if (open.isNotEmpty()) return open
            val since = Instant.now().minus(Duration.ofHours(ENDED_ORDER_HOURS))
            return orders.sortedByDescending { it.createdAt }.take(1)
                .filter { runCatching { Instant.parse(it.createdAt) }.getOrNull()?.isAfter(since) == true }
        }

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
    data object Order : BusinessSheet
    data object Queue : BusinessSheet
    data object RequestQuote : BusinessSheet
    data class QuoteOrder(val quote: BipeQuote) : BusinessSheet
    data class QuoteVisit(val quote: BipeQuote, val serviceId: String) : BusinessSheet
    data class QuoteBuild(val quote: BipeQuote?, val request: BipeQuoteRequest?) : BusinessSheet
    data object OwnerQueue : BusinessSheet
}

/**
 * The business tools of one DM: [MenuItems] go in the chat header menu, [Cards] above the timeline, [Sheets] once at the
 * end of the screen.
 */
class BusinessChatTools(session: AccountSession, media: MediaLoader, peerUserId: String) {
    val model = BusinessChatModel(BusinessApi(session), media, peerUserId)
    private var sheet by mutableStateOf<BusinessSheet?>(null)

    /** Spec order-customer cart: item ID to quantity. It stays while the chat is open, across catalog-view and the order. */
    val cart = mutableStateMapOf<String, Int>()

    val hasMenuItems: Boolean get() = !model.state.ownerMode && model.state.isCompany

    /** The chat header menu items (one per tool). Pedir orçamento shows for every company. */
    @Composable
    fun MenuItems(dismiss: () -> Unit) {
        val state = model.state
        if (state.ownerMode || !state.isCompany) return
        val open: (BusinessSheet) -> Unit = {
            dismiss()
            sheet = it
        }
        if (state.canBook) DropdownMenuItem(text = { Text(stringResource(R.string.bipe_agenda_book)) }, onClick = { open(BusinessSheet.Book()) })
        if (state.hasCatalog) DropdownMenuItem(text = { Text(stringResource(R.string.bipe_catalog_open)) }, onClick = { open(BusinessSheet.Catalog) })
        if (state.canOrder) DropdownMenuItem(text = { Text(stringResource(R.string.bipe_order_make)) }, onClick = { open(BusinessSheet.Order) })
        DropdownMenuItem(text = { Text(stringResource(R.string.bipe_quote_request)) }, onClick = { open(BusinessSheet.RequestQuote) })
        if (state.queueOpen || state.queuePlace != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_queue_join)) }, onClick = { open(BusinessSheet.Queue) })
        }
    }

    /** Spec business-chat-bar: the buttons under the name on a company's person-profile (ADR 0103: Agendar first). */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ProfileButtons() {
        val state = model.state
        if (state.ownerMode || !state.isCompany) return
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag(BipeScreen.BUSINESS_CHAT_BAR.id),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (state.canBook) Button(onClick = { sheet = BusinessSheet.Book() }) { Text(stringResource(R.string.bipe_agenda_book)) }
            if (state.hasCatalog) FilledTonalButton(onClick = { sheet = BusinessSheet.Catalog }) { Text(stringResource(R.string.bipe_catalog_open)) }
            if (state.canOrder) FilledTonalButton(onClick = { sheet = BusinessSheet.Order }) { Text(stringResource(R.string.bipe_order_make)) }
            FilledTonalButton(onClick = { sheet = BusinessSheet.RequestQuote }) { Text(stringResource(R.string.bipe_quote_request)) }
            if (state.queueOpen || state.queuePlace != null) {
                FilledTonalButton(onClick = { sheet = BusinessSheet.Queue }) { Text(stringResource(R.string.bipe_queue_join)) }
            }
        }
    }

    /**
     * The cards pinned above the timeline: agenda-booking-card, the order card, quote cards and the queue card. The queue
     * card reads again every 30 seconds while it shows (spec queue-customer load).
     */
    @Composable
    fun Cards() {
        val state = model.state
        if (!state.hasCards) return
        val label = stringResource(R.string.bipe_business_tools_a11y)
        val scope = rememberCoroutineScope()
        val reload: () -> Unit = { scope.launch { model.load() } }
        val place = state.queuePlace
        LaunchedEffect(place?.place?.id) {
            if (place == null) return@LaunchedEffect
            while (true) {
                delay(QUEUE_REFRESH_MS)
                model.refreshQueue()
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = CARDS_MAX_HEIGHT).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp).testTag(BipeScreen.BUSINESS_CHAT_BAR.id)
                .semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (place != null) {
                QueueCard(
                    position = place,
                    businessName = state.businessName,
                    customerName = state.customerName.takeIf { state.ownerMode },
                    onOpen = { sheet = if (state.ownerMode) BusinessSheet.OwnerQueue else BusinessSheet.Queue },
                )
            }
            state.orders.forEach { order ->
                OrderCard(
                    order = order,
                    ownerMode = state.ownerMode,
                    business = model.business,
                    route = state.route(model.peerUserId),
                    customerName = state.customerName.takeIf { state.ownerMode },
                    onChanged = reload,
                )
            }
            state.quoteRequests.forEach { request ->
                QuoteRequestCard(request, onAnswer = { sheet = BusinessSheet.QuoteBuild(quote = null, request = request) })
            }
            state.quotes.forEach { quote ->
                QuoteCard(
                    quote = quote,
                    ownerMode = state.ownerMode,
                    business = model.business,
                    route = state.route(model.peerUserId),
                    itemNames = state.itemNames,
                    onAcceptOrder = { sheet = BusinessSheet.QuoteOrder(quote) },
                    onAcceptBooking = { serviceId -> sheet = BusinessSheet.QuoteVisit(quote, serviceId) },
                    onRevise = { sheet = BusinessSheet.QuoteBuild(quote = quote, request = null) },
                    onChanged = reload,
                )
            }
            state.bookings.forEach { view ->
                BookingCard(
                    view = view,
                    ownerMode = state.ownerMode,
                    business = model.business,
                    onReschedule = { sheet = BusinessSheet.Book(moving = view) },
                    onOpenOwner = { sheet = BusinessSheet.Owner(view) },
                    onChanged = reload,
                )
            }
        }
    }

    /**
     * The flows the tools open: agenda-book, catalog-view, agenda-owner-booking, order-customer, queue-customer, the
     * quote request and accept steps, and on the company side quote-builder and queue-owner.
     */
    @Composable
    fun Sheets() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val state = model.state
        val route = state.route(model.peerUserId)
        val close: (Boolean) -> Unit = { changed ->
            sheet = null
            if (changed) scope.launch { model.load() }
        }
        when (val open = sheet) {
            null -> Unit
            is BusinessSheet.Book -> BusinessFullScreen(onDismiss = { close(false) }) {
                AgendaBookScreen(
                    business = model.business,
                    route = open.moving?.business ?: route,
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
                    route = route,
                    bookableServices = state.bookableServices,
                    onBook = { serviceId -> sheet = BusinessSheet.Book(serviceId = serviceId) },
                    onClose = { close(false) },
                    onAddToOrder = { itemId ->
                        cart[itemId] = ((cart[itemId] ?: 0) + 1).coerceAtMost(MAX_ORDER_QUANTITY)
                        sheet = BusinessSheet.Order
                    },
                )
            }
            // The chat with this customer is already open: openChat only closes the sheet.
            is BusinessSheet.Owner -> AgendaOwnerBookingSheet(
                business = model.business,
                booking = open.booking,
                onOpenChat = { close(false) },
                onClose = { changed -> close(changed) },
            )
            BusinessSheet.Order -> BusinessFullScreen(onDismiss = { close(false) }) {
                OrderCustomerScreen(
                    business = model.business,
                    route = route,
                    products = state.products,
                    cart = cart,
                    onClose = { sent ->
                        if (sent) {
                            cart.clear()
                            Toast.makeText(context, context.getString(R.string.bipe_order_sent), Toast.LENGTH_LONG).show()
                        }
                        close(sent)
                    },
                )
            }
            BusinessSheet.Queue -> BusinessFullScreen(onDismiss = { close(true) }) {
                QueueCustomerScreen(business = model.business, route = route, businessName = state.businessName, onClose = { close(true) })
            }
            BusinessSheet.RequestQuote -> RequestQuoteDialog(model.business, route, onClose = close)
            is BusinessSheet.QuoteOrder -> QuoteAcceptOrderDialog(model.business, route, open.quote, onClose = close)
            is BusinessSheet.QuoteVisit -> BusinessFullScreen(onDismiss = { close(false) }) {
                val quote = open.quote
                AgendaBookScreen(
                    business = model.business,
                    route = route,
                    preselectedServiceId = open.serviceId,
                    quoteAccept = { serviceId, agendaId, start ->
                        model.business.withToken { acceptQuoteForBooking(route, quote.id, quote.currentVersion, serviceId, agendaId, start, it) }
                    },
                    onClose = { booked ->
                        if (booked) Toast.makeText(context, context.getString(R.string.bipe_agenda_booked), Toast.LENGTH_LONG).show()
                        close(booked)
                    },
                )
            }
            is BusinessSheet.QuoteBuild -> BusinessFullScreen(onDismiss = { close(false) }) {
                QuoteBuilderScreen(
                    business = model.business,
                    quote = open.quote,
                    request = open.request,
                    customerName = state.customerName,
                    onClose = close,
                )
            }
            BusinessSheet.OwnerQueue -> BusinessFullScreen(onDismiss = { close(true) }) {
                // The chat is open already: the walk-in picker offers this customer only.
                QueueOwnerScreen(
                    business = model.business,
                    customers = listOf(model.peerUserId to state.customerName),
                    onClose = { close(true) },
                )
            }
        }
    }

    companion object {
        /** Spec queue-customer load: every 30 seconds while the card shows. */
        const val QUEUE_REFRESH_MS = 30_000L

        /** The pinned cards scroll inside this height, so the timeline keeps room. */
        private val CARDS_MAX_HEIGHT = 320.dp
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
