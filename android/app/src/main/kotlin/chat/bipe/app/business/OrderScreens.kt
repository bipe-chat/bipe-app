// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBookingCustomer
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.BipeOrder
import chat.bipe.shared.api.BipeOrderLineInput
import chat.bipe.shared.api.BipeOrderPolicy
import chat.bipe.shared.api.BipeOrderRefuseReason
import chat.bipe.shared.api.BipeOrderStatus
import chat.bipe.shared.api.BipeOwnerOrderAction
import chat.bipe.shared.api.FULFILMENT_DELIVERY
import chat.bipe.shared.api.FULFILMENT_PICKUP
import chat.bipe.shared.api.cancelOrder
import chat.bipe.shared.api.orderPolicy
import chat.bipe.shared.api.ownerCatalog
import chat.bipe.shared.api.ownerCreateOrder
import chat.bipe.shared.api.ownerOrderAction
import chat.bipe.shared.api.ownerOrders
import chat.bipe.shared.api.placeOrder
import chat.bipe.shared.api.publicCatalog
import chat.bipe.shared.api.refuseOrder
import chat.bipe.shared.api.setOrderPolicy
import chat.bipe.shared.generated.BipeScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Spec order-customer setQuantity: 0 to 999. */
internal const val MAX_ORDER_QUANTITY = 999

/** The owner's typed name of a walk-in or an order (contract BookingCustomer name). */
internal const val CUSTOMER_NAME_MAX = 120

/** The quantity row of a cart line: "−", the number and "+". Each button says the quantity it sets. */
@Composable
internal fun QuantityStepper(name: String, quantity: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val colors = BipeTheme.colors
    val label = stringResource(R.string.bipe_order_quantity)
    if (quantity <= 0) {
        TextButton(onClick = { onChange(1) }, enabled = enabled) { Text(stringResource(R.string.bipe_order_add)) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val less = "$label, $name: ${quantity - 1}"
        TextButton(onClick = { onChange(quantity - 1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = less }) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(quantity.toString(), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 24.dp))
        val more = "$label, $name: ${quantity + 1}"
        TextButton(
            onClick = { onChange(quantity + 1) },
            enabled = enabled && quantity < MAX_ORDER_QUANTITY,
            modifier = Modifier.semantics { contentDescription = more },
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Retirada or Entrega (contract fulfilment). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FulfilmentChoice(value: String, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = BipeTheme.colors
    Text(stringResource(R.string.bipe_order_fulfilment), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(FULFILMENT_PICKUP, FULFILMENT_DELIVERY).forEach { id ->
            FilterChip(selected = value == id, onClick = { onPick(id) }, enabled = enabled, label = { Text(stringResource(BusinessText.fulfilment(id))) })
        }
    }
}

/** Como você vai pagar: pay on site is the only choice (ADR 0080 not built; Bipe moves no money, ADR 0012). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaymentOnSite() {
    val colors = BipeTheme.colors
    Text(stringResource(R.string.bipe_order_payment), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.bipe_order_pay_on_site)) })
    }
}

/**
 * The customer of an owner-made order or walk-in: a person from the company's chats ([customers], Matrix ID and name)
 * or a typed name. [onChange] gets the customer, or `null` while none is chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomerChoice(customers: List<Pair<String, String>>, enabled: Boolean, onChange: (BipeBookingCustomer?) -> Unit) {
    val colors = BipeTheme.colors
    var fromChats by remember { mutableStateOf(customers.isNotEmpty()) }
    var customerId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(fromChats, customerId, name) {
        onChange(
            if (fromChats) customerId?.let { BipeBookingCustomer(userId = it) }
            else name.trim().takeIf { it.isNotEmpty() }?.let { BipeBookingCustomer(name = it) },
        )
    }
    Text(stringResource(R.string.bipe_agenda_customer), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (customers.isNotEmpty()) {
            FilterChip(selected = fromChats, onClick = { fromChats = true }, enabled = enabled,
                label = { Text(stringResource(R.string.bipe_agenda_customer_from_chats)) })
        }
        FilterChip(selected = !fromChats, onClick = { fromChats = false }, enabled = enabled, label = { Text(stringResource(R.string.bipe_agenda_customer_name)) })
    }
    if (fromChats) {
        PickerField(stringResource(R.string.bipe_agenda_customer_from_chats), customers.firstOrNull { it.first == customerId }?.second.orEmpty(),
            customers, enabled = enabled) { customerId = it }
    } else {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(CUSTOMER_NAME_MAX) },
            label = { Text(stringResource(R.string.bipe_agenda_customer_name)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The cart lines in the order the products show, with the preview total (the server adds the real one). */
private fun cartLines(products: List<BipeCatalogItem>, cart: Map<String, Int>): List<Pair<BipeCatalogItem, Int>> =
    products.mapNotNull { item -> cart[item.id]?.takeIf { it > 0 }?.let { item to it } }

private fun cartTotal(lines: List<Pair<BipeCatalogItem, Int>>): Long = lines.sumOf { (item, quantity) -> (item.price.amountCents ?: 0L) * quantity }

/** The product list of a cart: name, price and the quantity stepper. */
@Composable
private fun ProductRows(products: List<BipeCatalogItem>, cart: MutableMap<String, Int>, enabled: Boolean) {
    val colors = BipeTheme.colors
    products.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogPhoto(item.photo, item.name, 48.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                Text(BusinessText.reais(item.price.amountCents ?: 0), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            QuantityStepper(item.name, cart[item.id] ?: 0, enabled) { quantity ->
                if (quantity <= 0) cart.remove(item.id) else cart[item.id] = quantity.coerceAtMost(MAX_ORDER_QUANTITY)
            }
        }
        HorizontalDivider(color = colors.lineSubtle)
    }
}

/**
 * Spec order-customer: the cart from the catálogo's fixed-price products ([products], read again on open), retirada
 * or entrega, pay on site, the address hint and the total preview. [cart] lives in the chat, so catalog-view adds to
 * it. [onClose] says whether the order went.
 */
@Composable
fun OrderCustomerScreen(
    business: BusinessApi,
    route: String,
    products: List<BipeCatalogItem>,
    cart: SnapshotStateMap<String, Int>,
    onClose: (Boolean) -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(products) }
    var fulfilment by remember { mutableStateOf(FULFILMENT_PICKUP) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(route, attempt) {
        // Fresh prices and stock; the cart keeps only what can still be ordered.
        runCatching { business.api.publicCatalog(route).filter(::isOrderable) }.getOrNull()?.let { fresh ->
            items = fresh
            cart.keys.toList().filter { id -> fresh.none { it.id == id } }.forEach { cart.remove(it) }
        }
    }
    val lines = cartLines(items, cart)

    fun send() {
        sending = true
        error = null
        scope.launch {
            try {
                business.withToken { placeOrder(route, lines.map { (item, quantity) -> BipeOrderLineInput(item.id, quantity) }, fulfilment, it) }
                onClose(true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BusinessText.error(context, failure, R.string.bipe_order_error)
                val code = (failure as? BipeApiException.Server)?.code
                if (code == BipeApiErrorCode.ITEM_UNAVAILABLE || code == BipeApiErrorCode.PRICE_NOT_FIXED) attempt++
            }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.ORDER_CUSTOMER.id)) {
        GroupTopBar(stringResource(R.string.bipe_order_make), onBack = { onClose(false) }.takeUnless { sending }, backIcon = PhosphorIcon.X)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProductRows(items, cart, enabled = !sending)
            Text(stringResource(R.string.bipe_order_cart), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary,
                modifier = Modifier.padding(top = 12.dp))
            if (lines.isEmpty()) {
                Text(stringResource(R.string.bipe_order_empty), color = colors.inkSecondary)
            } else {
                lines.forEach { (item, quantity) ->
                    Text(
                        stringResource(R.string.bipe_order_line, quantity.toString(), item.name) + " · " +
                            BusinessText.reais((item.price.amountCents ?: 0) * quantity),
                        color = colors.inkPrimary,
                    )
                }
                Text(
                    stringResource(R.string.bipe_order_total) + " · " + BusinessText.reais(cartTotal(lines)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
            }
            FulfilmentChoice(fulfilment, enabled = !sending) { fulfilment = it }
            PaymentOnSite()
            Text(stringResource(R.string.bipe_order_address_in_chat), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            error?.let { Text(it, color = colors.trustDangerInk) }
            Button(onClick = ::send, enabled = !sending && lines.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (sending) CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
                else Text(stringResource(R.string.bipe_order_send))
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The order card (spec order-customer card, order-owner row): lines, total, retirada or entrega, pay on site and the
 * status, with a refusal's reason. The customer can cancel an open order; the owner ([ownerMode]) moves it on:
 * Aceitar or Recusar, Pronto, Entregue, Cancelar. It never says paid (security rule 12).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderCard(
    order: BipeOrder,
    ownerMode: Boolean,
    business: BusinessApi,
    route: String,
    customerName: String?,
    onChanged: () -> Unit,
    onOpenChat: ((String) -> Unit)? = null,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = order.orderStatus
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var refusing by remember { mutableStateOf(false) }

    fun perform(call: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                call()
                onChanged()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BusinessText.error(context, failure, R.string.bipe_order_error)
            }
            working = false
        }
    }

    Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag(BipeScreen.ORDER_CUSTOMER.id)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.CHECKS.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.bipe_order_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentInk,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(BusinessText.orderStatus(status)), style = MaterialTheme.typography.labelMedium, color = orderStatusColor(status))
            }
            if (ownerMode) {
                (customerName ?: order.customer.name)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary) }
                runCatching { Instant.parse(order.createdAt) }.getOrNull()?.let {
                    Text(BusinessText.date(it) + " · " + BusinessText.time(it), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
            order.lines.forEach { Text(BusinessText.line(context, it), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary) }
            Text(
                stringResource(R.string.bipe_order_total) + " · " + BusinessText.reais(order.totalAmountCents),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(
                stringResource(BusinessText.fulfilment(order.fulfilment)) + " · " + stringResource(R.string.bipe_order_pay_on_site),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
            if (status == BipeOrderStatus.REFUSED) {
                order.reason?.let {
                    Text(stringResource(R.string.bipe_order_refuse_reason_label, stringResource(BusinessText.refuseReason(it))),
                        style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (ownerMode) {
                    when (status) {
                        BipeOrderStatus.SENT -> {
                            TextButton(enabled = !working, onClick = { perform { business.withToken { ownerOrderAction(order.id, BipeOwnerOrderAction.ACCEPT, it) } } }) {
                                Text(stringResource(R.string.bipe_order_accept))
                            }
                            TextButton(enabled = !working, onClick = { refusing = true }) { Text(stringResource(R.string.bipe_order_refuse)) }
                        }
                        BipeOrderStatus.ACCEPTED -> TextButton(enabled = !working, onClick = {
                            perform { business.withToken { ownerOrderAction(order.id, BipeOwnerOrderAction.READY, it) } }
                        }) { Text(stringResource(R.string.bipe_order_mark_ready)) }
                        BipeOrderStatus.READY -> TextButton(enabled = !working, onClick = {
                            perform { business.withToken { ownerOrderAction(order.id, BipeOwnerOrderAction.DONE, it) } }
                        }) { Text(stringResource(R.string.bipe_order_mark_done)) }
                        else -> Unit
                    }
                    val userId = order.customer.userId
                    if (onOpenChat != null && userId != null) {
                        TextButton(onClick = { onOpenChat(userId) }) { Text(stringResource(R.string.bipe_app_person_message)) }
                    }
                }
                if (status.isOpen) {
                    TextButton(enabled = !working, onClick = { confirmCancel = true }) {
                        Text(stringResource(R.string.bipe_order_cancel), color = colors.trustDangerInk)
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.bipe_order_cancel_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    perform {
                        if (ownerMode) business.withToken { ownerOrderAction(order.id, BipeOwnerOrderAction.CANCEL, it) }
                        else business.withToken { cancelOrder(route, order.id, it) }
                    }
                }) { Text(stringResource(R.string.bipe_order_cancel), color = colors.trustDangerInk) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
            containerColor = colors.surfaceRaised,
        )
    }
    if (refusing) {
        RefuseOrderDialog(onDismiss = { refusing = false }) { reason ->
            refusing = false
            perform { business.withToken { refuseOrder(order.id, reason, it) } }
        }
    }
}

/** Spec order-owner refuse: bipe_order_refuse_title with the four fixed reasons. */
@Composable
private fun RefuseOrderDialog(onDismiss: () -> Unit, onPick: (BipeOrderRefuseReason) -> Unit) {
    val colors = BipeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bipe_order_refuse_title)) },
        text = {
            Column {
                BipeOrderRefuseReason.entries.forEach { reason ->
                    TextButton(onClick = { onPick(reason) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(BusinessText.refuseReason(reason)), color = colors.inkPrimary, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

@Composable
private fun orderStatusColor(status: BipeOrderStatus) = when (status) {
    BipeOrderStatus.ACCEPTED, BipeOrderStatus.READY, BipeOrderStatus.DONE -> BipeTheme.colors.trustVerifiedInk
    BipeOrderStatus.SENT -> BipeTheme.colors.trustCautionInk
    BipeOrderStatus.REFUSED, BipeOrderStatus.CANCELLED -> BipeTheme.colors.inkSecondary
}

/** What order-owner has open. */
private sealed interface OrderOwnerSheet {
    data object New : OrderOwnerSheet
    data object Settings : OrderOwnerSheet
}

/**
 * Spec order-owner: the company's orders, newest first, with the card actions, Novo pedido and the order days and
 * cut-off. [customers] are the company's DMs (Matrix ID and name); [onOpenChat] takes a customer's Matrix ID.
 */
@Composable
fun OrderOwnerScreen(business: BusinessApi, customers: List<Pair<String, String>>, onOpenChat: ((String) -> Unit)?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    var orders by remember { mutableStateOf<List<BipeOrder>?>(null) }
    val names = remember { mutableStateMapOf<String, String>() }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var sheet by remember { mutableStateOf<OrderOwnerSheet?>(null) }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(attempt, foreground) {
        failed = false
        try {
            val list = business.withToken { ownerOrders(it) }.sortedByDescending { it.createdAt }
            orders = list
            list.mapNotNull { it.customer.userId }.distinct().forEach { names[it] = business.displayName(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.ORDER_OWNER.id)) {
        GroupTopBar(stringResource(R.string.bipe_orders_title), onBack = onClose) {
            TextButton(onClick = { sheet = OrderOwnerSheet.New }) { Text(stringResource(R.string.bipe_order_new)) }
            IconButton(onClick = { sheet = OrderOwnerSheet.Settings }) {
                Icon(painterResource(PhosphorIcon.GEAR.drawable), contentDescription = stringResource(R.string.bipe_order_settings), tint = colors.glyphPrimary)
            }
        }
        val list = orders
        when {
            failed && list == null -> LoadFailed(stringResource(R.string.bipe_order_load_failed)) { attempt++ }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.bipe_order_owner_empty), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        ownerMode = true,
                        business = business,
                        route = business.session.userId,
                        customerName = order.customer.userId?.let { names[it] } ?: order.customer.name,
                        onChanged = { attempt++ },
                        onOpenChat = onOpenChat,
                    )
                }
                item(key = "end") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
    when (sheet) {
        null -> Unit
        OrderOwnerSheet.New -> BusinessFullScreen(onDismiss = { sheet = null }) {
            OwnerNewOrderScreen(business, customers, onClose = { made -> sheet = null; if (made) attempt++ })
        }
        OrderOwnerSheet.Settings -> OrderSettingsDialog(business, onClose = { sheet = null })
    }
}

/** A failed first load: the text and Tentar de novo. */
@Composable
internal fun LoadFailed(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = BipeTheme.colors.inkSecondary, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.bipe_app_retry)) }
    }
}

/** Spec order-owner newOrder: the catálogo's fixed-price products for a typed name or a person from the chats. */
@Composable
private fun OwnerNewOrderScreen(business: BusinessApi, customers: List<Pair<String, String>>, onClose: (Boolean) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<List<BipeCatalogItem>?>(null) }
    val cart = remember { mutableStateMapOf<String, Int>() }
    var fulfilment by remember { mutableStateOf(FULFILMENT_PICKUP) }
    var customer by remember { mutableStateOf<BipeBookingCustomer?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        products = runCatching { business.withToken { ownerCatalog(it) }.filter(::isOrderable) }.getOrNull()
        if (products == null) error = context.getString(R.string.bipe_order_load_failed)
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.ORDER_OWNER.id)) {
        GroupTopBar(stringResource(R.string.bipe_order_new), onBack = { onClose(false) }.takeUnless { saving }, backIcon = PhosphorIcon.X)
        val list = products
        if (list == null) {
            if (error != null) LoadFailed(error.orEmpty()) { error = null; attempt++ }
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            return@Column
        }
        val lines = cartLines(list, cart)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProductRows(list, cart, enabled = !saving)
            if (lines.isNotEmpty()) {
                Text(
                    stringResource(R.string.bipe_order_total) + " · " + BusinessText.reais(cartTotal(lines)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            CustomerChoice(customers, enabled = !saving) { customer = it }
            FulfilmentChoice(fulfilment, enabled = !saving) { fulfilment = it }
            error?.let { Text(it, color = colors.trustDangerInk) }
            Button(
                enabled = !saving && lines.isNotEmpty() && customer != null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                onClick = {
                    val who = customer ?: return@Button
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            business.withToken { ownerCreateOrder(lines.map { (item, quantity) -> BipeOrderLineInput(item.id, quantity) }, fulfilment, who, it) }
                            onClose(true)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = BusinessText.error(context, failure, R.string.bipe_order_error)
                        }
                        saving = false
                    }
                },
            ) { Text(stringResource(R.string.bipe_app_save)) }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** The cut-off choices: every half hour, and 23:59 (the default, the end of the day). */
private val cutoffTimes: List<LocalTime> = dayTimes(30) + LocalTime.of(23, 59)

/** Spec order-owner saveSettings: the order weekdays and the local cut-off; the time zone stays as read. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderSettingsDialog(business: BusinessApi, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf<BipeOrderPolicy?>(null) }
    val weekdays = remember { mutableStateListOf<Int>() }
    var cutoff by remember { mutableStateOf(LocalTime.of(23, 59)) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { business.withToken { orderPolicy(it) } }
            .onSuccess { read ->
                policy = read
                weekdays.clear()
                weekdays.addAll(read.weekdays)
                cutoff = runCatching { LocalTime.parse(read.cutoff) }.getOrDefault(LocalTime.of(23, 59))
            }
            .onFailure { error = context.getString(R.string.bipe_order_load_failed) }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(stringResource(R.string.bipe_order_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (policy == null && error == null) CircularProgressIndicator(color = colors.accentFill)
                if (policy != null) {
                    Text(stringResource(R.string.bipe_order_opening_days), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val iso = day.value
                            FilterChip(
                                selected = iso in weekdays,
                                onClick = { if (iso in weekdays) weekdays.remove(iso) else weekdays.add(iso) },
                                enabled = !working,
                                label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            )
                        }
                    }
                    PickerField(stringResource(R.string.bipe_order_cutoff), BusinessText.clock(cutoff), cutoffTimes.map { it to BusinessText.clock(it) },
                        enabled = !working) { cutoff = it }
                }
                error?.let { Text(it, color = colors.trustDangerInk) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && policy != null, onClick = {
                val read = policy ?: return@TextButton
                working = true
                error = null
                scope.launch {
                    val saved = BipeOrderPolicy(read.timeZone, weekdays.sorted(), String.format(Locale.ROOT, "%02d:%02d", cutoff.hour, cutoff.minute))
                    runCatching { business.withToken { setOrderPolicy(saved, it) } }
                        .onSuccess { onClose() }
                        .onFailure { error = BusinessText.error(context, it, R.string.bipe_order_error) }
                    working = false
                }
            }) { Text(stringResource(R.string.bipe_app_save)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}
