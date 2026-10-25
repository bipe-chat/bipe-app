// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.myBookings
import chat.bipe.shared.api.publicAgendas
import chat.bipe.shared.api.publicCatalog
import chat.bipe.shared.api.publicServices
import chat.bipe.shared.api.resolveHandles
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the business tools section has open. */
private enum class ToolScreen { AGENDA, CATALOG, ORDERS, QUOTES, QUEUE, BRAND_MARKS }

/**
 * Spec app-tabs and business-tools: the Trabalho tab of a company account, in the place of Grupos.
 * [onOpenChat] takes a room ID and shows it in Conversas.
 */
@Composable
fun WorkScreen(session: AccountSession, chatList: ChatListModel, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.bipe_tab_work), style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
            BusinessToolsSection(session, chatList, onOpenChat)
        }
    }
}

/**
 * Spec business-tools: the Ferramentas do negócio section on the Trabalho tab of a company account. Agenda opens
 * agenda-owner, Catálogo catalog-owner, Pedidos order-owner, Orçamentos quote-builder and Fila virtual queue-owner.
 * [chatList] gives the company's DMs (owner orders and walk-ins); [onOpenChat] takes a room ID.
 */
@Composable
fun BusinessToolsSection(session: AccountSession, chatList: ChatListModel, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val business = remember(session) { BusinessApi(session) }
    // null: not loaded; Result of the state, whose null value means not activated.
    var agenda by remember(session) { mutableStateOf<Result<BipeAgendaState?>?>(null) }
    var open by remember { mutableStateOf<ToolScreen?>(null) }
    val chats by chatList.chats.collectAsState()
    val customers = chats.filter { it.isDm && it.peerUserId != null }.map { it.peerUserId!! to it.name }
    // order-owner and quote-builder openChat: the DM with the customer, after the tool closes.
    val openCustomerChat: (String) -> Unit = { userId ->
        open = null
        scope.launch { NativeDmSelection.roomId(userId, session)?.let(onOpenChat) }
    }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(business, foreground) { agenda = runCatching { business.withToken { myAgenda(it) } } }
    Column(Modifier.fillMaxWidth().testTag(BipeScreen.BUSINESS_TOOLS.id)) {
        Text(stringResource(R.string.bipe_business_tools_title), style = MaterialTheme.typography.labelLarge, color = colors.accentInk,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
        val state = agenda?.getOrNull()
        val subtitle = when {
            agenda == null || agenda?.isFailure == true -> null
            state == null -> stringResource(R.string.bipe_agenda_activate)
            state.published -> stringResource(R.string.bipe_agenda_published)
            else -> stringResource(R.string.bipe_agenda_not_published)
        }
        ToolRow(PhosphorIcon.CLOCK, stringResource(R.string.bipe_tab_agenda), subtitle) { open = ToolScreen.AGENDA }
        ToolRow(PhosphorIcon.NOTEPAD, stringResource(R.string.bipe_catalog_title), null) { open = ToolScreen.CATALOG }
        ToolRow(PhosphorIcon.CHECKS, stringResource(R.string.bipe_orders_title), null) { open = ToolScreen.ORDERS }
        ToolRow(PhosphorIcon.HANDSHAKE, stringResource(R.string.bipe_quotes_title), null) { open = ToolScreen.QUOTES }
        ToolRow(PhosphorIcon.USERS_THREE, stringResource(R.string.bipe_queue_title), null) { open = ToolScreen.QUEUE }
        ToolRow(PhosphorIcon.SEAL_CHECK, stringResource(R.string.bipe_brand_marks_title), null) { open = ToolScreen.BRAND_MARKS }
        Text(stringResource(R.string.bipe_business_tools_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
    open?.let { screen ->
        BusinessFullScreen(onDismiss = { open = null }) {
            when (screen) {
                ToolScreen.AGENDA -> AgendaOwnerScreen(session, chatList, onOpenChat = { roomId ->
                    open = null
                    onOpenChat(roomId)
                })
                ToolScreen.CATALOG -> CatalogOwnerScreen(business, onClose = { open = null })
                ToolScreen.ORDERS -> OrderOwnerScreen(business, customers, onOpenChat = openCustomerChat, onClose = { open = null })
                ToolScreen.QUOTES -> QuoteOwnerScreen(business, onOpenChat = openCustomerChat, onClose = { open = null })
                ToolScreen.QUEUE -> QueueOwnerScreen(business, customers, onClose = { open = null })
                ToolScreen.BRAND_MARKS -> BrandMarksScreen(business, onClose = { open = null })
            }
        }
    }
}

@Composable
private fun ToolRow(icon: PhosphorIcon, title: String, subtitle: String?, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = colors.inkPrimary) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text, color = colors.inkSecondary) } },
        leadingContent = { Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.glyphPrimary) },
        trailingContent = { Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.glyphSecondary) },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Spec agenda-my-bookings: the customer's upcoming bookings across businesses, drawn as agenda-booking-card. A tap
 * opens the chat with the business; [onOpenChat] takes the business's Matrix ID.
 */
@Composable
fun MyBookingsScreen(session: AccountSession, onOpenChat: (String) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val business = remember(session) { BusinessApi(session) }
    var bookings by remember { mutableStateOf<List<BookingView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var moving by remember { mutableStateOf<BookingView?>(null) }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(attempt, foreground) {
        failed = false
        try {
            bookings = loadMyBookings(business)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.AGENDA_MY_BOOKINGS.id)) {
        GroupTopBar(stringResource(R.string.bipe_agenda_my_bookings), onBack = onClose)
        val list = bookings
        when {
            failed && list == null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.bipe_agenda_load_failed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.bipe_agenda_no_bookings), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, view -> view.booking.id }) { index, view ->
                    if (index > 0) HorizontalDivider()
                    BookingCard(
                        view = view,
                        ownerMode = false,
                        business = business,
                        onReschedule = { moving = view },
                        onOpenOwner = {},
                        onChanged = { attempt++ },
                        onOpen = { onOpenChat(view.booking.businessId) },
                        inList = true,
                    )
                }
            }
        }
    }
    moving?.let { view ->
        BusinessFullScreen(onDismiss = { moving = null }) {
            AgendaBookScreen(
                business = business,
                route = view.business,
                preselectedServiceId = view.booking.serviceId,
                moving = view.booking,
                onClose = { done ->
                    moving = null
                    if (done) {
                        Toast.makeText(context, context.getString(R.string.bipe_agenda_booked), Toast.LENGTH_LONG).show()
                        attempt++
                    }
                },
            )
        }
    }
}

/** Action load of agenda-my-bookings: GET /v1/me/bookings, then the names from each business's public services. */
private suspend fun loadMyBookings(business: BusinessApi): List<BookingView> {
    val bookings = business.withToken { myBookings(it) }.filter { it.bookingStatus.isOpen }
    val businesses = bookings.map { it.businessId }.distinct()
    val handles = if (businesses.isEmpty()) emptyMap() else runCatching { business.withToken { resolveHandles(businesses, it) } }.getOrNull().orEmpty()
    val names = mutableMapOf<String, Map<String, String>>()
    val agendas = mutableMapOf<String, Map<String, String>>()
    for (id in businesses) {
        val route = handles[id] ?: id
        val services = runCatching { business.api.publicServices(route) }.getOrNull().orEmpty()
        val catalog = if (bookings.all { b -> b.businessId != id || services.any { it.id == b.serviceId } }) emptyList()
        else runCatching { business.api.publicCatalog(route) }.getOrNull().orEmpty()
        names[id] = (catalog + services).associate { it.id to it.name }
        agendas[id] = runCatching { business.api.publicAgendas(route, null) }.getOrNull().orEmpty().associate { it.id to it.name }
    }
    return bookings.sortedBy { it.start }.map { booking ->
        val id = booking.businessId
        BookingView(
            booking = booking,
            business = handles[id] ?: id,
            businessName = business.displayName(id),
            serviceName = names[id]?.get(booking.serviceId).orEmpty(),
            agendaName = agendas[id]?.get(booking.agendaId),
            customerName = null,
        )
    }
}
