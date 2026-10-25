// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.BipeQuote
import chat.bipe.shared.api.BipeQuoteLineInput
import chat.bipe.shared.api.BipeQuoteRequest
import chat.bipe.shared.api.BipeQuoteStatus
import chat.bipe.shared.api.BipeQuoteVersionInput
import chat.bipe.shared.api.FULFILMENT_PICKUP
import chat.bipe.shared.api.acceptQuoteForOrder
import chat.bipe.shared.api.answerQuoteRequest
import chat.bipe.shared.api.ownerCatalog
import chat.bipe.shared.api.ownerQuoteRequests
import chat.bipe.shared.api.ownerQuotes
import chat.bipe.shared.api.refuseQuote
import chat.bipe.shared.api.requestQuote
import chat.bipe.shared.api.reviseQuote
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Days the validity picker reaches ahead, and the default. */
private const val VALIDITY_DAYS = 90L
private const val DEFAULT_VALIDITY_DAYS = 7L

/** Contract QuoteLine name and the server's line limit. */
private const val LINE_NAME_MAX = 200
private const val MAX_LINES = 50

/**
 * Spec quote-card request: the request body says the description and photos go in the chat, then POST
 * .../quotes/requests and bipe_quote_requested. [onClose] says whether a request went.
 */
@Composable
fun RequestQuoteDialog(business: BusinessApi, route: String, onClose: (Boolean) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) onClose(false) },
        title = { Text(stringResource(R.string.bipe_quote_request)) },
        text = {
            Column(Modifier.testTag(BipeScreen.QUOTE_CARD.id), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bipe_quote_request_body), color = colors.inkSecondary)
                error?.let { Text(it, color = colors.trustDangerInk) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                error = null
                scope.launch {
                    runCatching { business.withToken { requestQuote(route, it) } }
                        .onSuccess {
                            Toast.makeText(context, context.getString(R.string.bipe_quote_requested), Toast.LENGTH_LONG).show()
                            onClose(true)
                        }
                        .onFailure { error = BusinessText.error(context, it, R.string.bipe_quote_error) }
                    working = false
                }
            }) { Text(stringResource(R.string.bipe_quote_request)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { onClose(false) }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/**
 * Spec quote-card: lines, total, validity, version and status of the version on screen; older versions read only.
 * The customer accepts into an order ([onAcceptOrder]) or, with a visit service, a booking ([onAcceptBooking] takes the
 * service ID), or refuses. The owner ([ownerMode]) sends a new version ([onRevise]). [itemNames] names the visit service.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuoteCard(
    quote: BipeQuote,
    ownerMode: Boolean,
    business: BusinessApi,
    route: String,
    itemNames: Map<String, String>,
    onAcceptOrder: () -> Unit,
    onAcceptBooking: (String) -> Unit,
    onRevise: () -> Unit,
    onChanged: () -> Unit,
    customerName: String? = null,
    onOpenChat: ((String) -> Unit)? = null,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = quote.quoteStatus
    var shownVersion by remember(quote.id, quote.currentVersion) { mutableStateOf(quote.currentVersion) }
    var showsVersions by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val version = quote.versions.firstOrNull { it.version == shownVersion } ?: quote.current
    val isCurrent = version?.version == quote.currentVersion
    Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag(BipeScreen.QUOTE_CARD.id)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.HANDSHAKE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.bipe_quote_card_title) + " · " + stringResource(R.string.bipe_quote_version, (version?.version ?: quote.currentVersion).toString()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentInk,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) Text(stringResource(BusinessText.quoteStatus(status)), style = MaterialTheme.typography.labelMedium, color = quoteStatusColor(status))
            }
            if (ownerMode) customerName?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary) }
            version?.lines?.forEach { Text(BusinessText.line(context, it), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary) }
            version?.let {
                Text(
                    stringResource(R.string.bipe_quote_total) + " · " + BusinessText.reais(it.totalAmountCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                runCatching { Instant.parse(it.validUntil) }.getOrNull()?.let { until ->
                    Text(stringResource(R.string.bipe_quote_valid_until, BusinessText.date(until)), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
                it.visitServiceId?.let { id ->
                    Text(stringResource(R.string.bipe_quote_visit) + ": " + (itemNames[id] ?: ""), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
            if (quote.versions.size > 1) {
                TextButton(onClick = { showsVersions = !showsVersions }) { Text(stringResource(R.string.bipe_quote_previous_versions)) }
                if (showsVersions) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quote.versions.sortedByDescending { it.version }.forEach { item ->
                            FilterChip(
                                selected = item.version == version?.version,
                                onClick = { shownVersion = item.version },
                                label = { Text(stringResource(R.string.bipe_quote_version, item.version.toString())) },
                            )
                        }
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (ownerMode) {
                    if (status == BipeQuoteStatus.OFFERED || status == BipeQuoteStatus.EXPIRED) {
                        TextButton(onClick = onRevise) { Text(stringResource(R.string.bipe_quote_send_revision)) }
                    }
                    if (onOpenChat != null) TextButton(onClick = { onOpenChat(quote.customerId) }) { Text(stringResource(R.string.bipe_app_person_message)) }
                } else if (status == BipeQuoteStatus.OFFERED && isCurrent && version != null) {
                    TextButton(enabled = !working, onClick = onAcceptOrder) { Text(stringResource(R.string.bipe_quote_accept_order)) }
                    version.visitServiceId?.let { serviceId ->
                        TextButton(enabled = !working, onClick = { onAcceptBooking(serviceId) }) { Text(stringResource(R.string.bipe_quote_accept_booking)) }
                    }
                    TextButton(enabled = !working, onClick = {
                        working = true
                        error = null
                        scope.launch {
                            runCatching { business.withToken { refuseQuote(route, quote.id, quote.currentVersion, it) } }
                                .onSuccess { onChanged() }
                                .onFailure { failure ->
                                    error = BusinessText.error(context, failure, R.string.bipe_quote_error)
                                    // A new version arrived: show it.
                                    if ((failure as? BipeApiException.Server)?.code == BipeApiErrorCode.QUOTE_VERSION_CHANGED) onChanged()
                                }
                            working = false
                        }
                    }) { Text(stringResource(R.string.bipe_quote_decline), color = colors.trustDangerInk) }
                }
            }
        }
    }
}

@Composable
private fun quoteStatusColor(status: BipeQuoteStatus) = when (status) {
    BipeQuoteStatus.ACCEPTED -> BipeTheme.colors.trustVerifiedInk
    BipeQuoteStatus.OFFERED -> BipeTheme.colors.trustCautionInk
    BipeQuoteStatus.REFUSED, BipeQuoteStatus.EXPIRED -> BipeTheme.colors.inkSecondary
}

/** Spec quote-card acceptOrder: retirada or entrega, pay on site, then accept with target order. */
@Composable
fun QuoteAcceptOrderDialog(business: BusinessApi, route: String, quote: BipeQuote, onClose: (Boolean) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fulfilment by remember { mutableStateOf(FULFILMENT_PICKUP) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) onClose(false) },
        title = { Text(stringResource(R.string.bipe_quote_accept_order)) },
        text = {
            Column(Modifier.testTag(BipeScreen.QUOTE_CARD.id), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quote.current?.let {
                    Text(stringResource(R.string.bipe_quote_total) + " · " + BusinessText.reais(it.totalAmountCents), color = colors.inkPrimary)
                }
                FulfilmentChoice(fulfilment, enabled = !working) { fulfilment = it }
                PaymentOnSite()
                Text(stringResource(R.string.bipe_order_address_in_chat), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                error?.let { Text(it, color = colors.trustDangerInk) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                error = null
                scope.launch {
                    runCatching { business.withToken { acceptQuoteForOrder(route, quote.id, quote.currentVersion, fulfilment, it) } }
                        .onSuccess {
                            Toast.makeText(context, context.getString(R.string.bipe_order_sent), Toast.LENGTH_LONG).show()
                            onClose(true)
                        }
                        .onFailure { failure ->
                            val message = BusinessText.error(context, failure, R.string.bipe_quote_error)
                            error = message
                            val code = (failure as? BipeApiException.Server)?.code
                            // The card must show the new version or the expiry: close and reload.
                            if (code == BipeApiErrorCode.QUOTE_VERSION_CHANGED || code == BipeApiErrorCode.QUOTE_EXPIRED) {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                onClose(true)
                            }
                        }
                    working = false
                }
            }) { Text(stringResource(R.string.bipe_quote_accept_order)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { onClose(false) }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** The company side of the chat: a quote request of this customer with no quote yet, and Responder com orçamento. */
@Composable
fun QuoteRequestCard(request: BipeQuoteRequest, onAnswer: () -> Unit) {
    val colors = BipeTheme.colors
    Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag(BipeScreen.QUOTE_CARD.id)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.HANDSHAKE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.bipe_quote_requests_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
            }
            RequestedAt(request)
            TextButton(onClick = onAnswer) { Text(stringResource(R.string.bipe_quote_answer)) }
        }
    }
}

@Composable
private fun RequestedAt(request: BipeQuoteRequest) {
    val instant = runCatching { Instant.parse(request.createdAt) }.getOrNull() ?: return
    Text(
        stringResource(R.string.bipe_quote_requested_at, BusinessText.date(instant) + " · " + BusinessText.time(instant)),
        style = MaterialTheme.typography.bodySmall,
        color = BipeTheme.colors.inkSecondary,
    )
}

/** What the owner's quote list has open. */
private data class QuoteDraftTarget(val quote: BipeQuote?, val request: BipeQuoteRequest?, val customerName: String)

/**
 * Spec quote-builder, list part: the requests with no quote (newest first) and the quotes with their status. Each
 * opens the builder; [onOpenChat] takes a customer's Matrix ID.
 */
@Composable
fun QuoteOwnerScreen(business: BusinessApi, onOpenChat: ((String) -> Unit)?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    var requests by remember { mutableStateOf<List<BipeQuoteRequest>?>(null) }
    var quotes by remember { mutableStateOf<List<BipeQuote>>(emptyList()) }
    var itemNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val names = remember { mutableStateMapOf<String, String>() }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf<QuoteDraftTarget?>(null) }
    val foreground = rememberForegroundRevision()
    LaunchedEffect(attempt, foreground) {
        failed = false
        try {
            val open = business.withToken { ownerQuoteRequests(it) }
            quotes = business.withToken { ownerQuotes(it) }
            requests = open
            itemNames = runCatching { business.withToken { ownerCatalog(it) } }.getOrNull().orEmpty().associate { it.id to it.name }
            (open.map { it.customerId } + quotes.map { it.customerId }).distinct().forEach { names[it] = business.displayName(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.QUOTE_BUILDER.id)) {
        GroupTopBar(stringResource(R.string.bipe_quotes_title), onBack = onClose)
        val open = requests
        when {
            failed && open == null -> LoadFailed(stringResource(R.string.bipe_quote_load_failed)) { attempt++ }
            open == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            open.isEmpty() && quotes.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.bipe_quote_owner_empty), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (open.isNotEmpty()) item(key = "requests") { GroupSectionHeader(stringResource(R.string.bipe_quote_requests_title)) }
                items(open, key = { "r-" + it.id }) { request ->
                    val name = names[request.customerId].orEmpty()
                    Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                            RequestedAt(request)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { draft = QuoteDraftTarget(null, request, name) }) { Text(stringResource(R.string.bipe_quote_answer)) }
                                if (onOpenChat != null) {
                                    TextButton(onClick = { onOpenChat(request.customerId) }) { Text(stringResource(R.string.bipe_app_person_message)) }
                                }
                            }
                        }
                    }
                }
                if (quotes.isNotEmpty()) item(key = "quotes") { GroupSectionHeader(stringResource(R.string.bipe_quotes_title)) }
                items(quotes, key = { "q-" + it.id }) { quote ->
                    val name = names[quote.customerId].orEmpty()
                    QuoteCard(
                        quote = quote,
                        ownerMode = true,
                        business = business,
                        route = business.session.userId,
                        itemNames = itemNames,
                        onAcceptOrder = {},
                        onAcceptBooking = {},
                        onRevise = { draft = QuoteDraftTarget(quote, null, name) },
                        onChanged = { attempt++ },
                        customerName = name,
                        onOpenChat = onOpenChat,
                    )
                }
                item(key = "end") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
    draft?.let { target ->
        BusinessFullScreen(onDismiss = { draft = null }) {
            QuoteBuilderScreen(business, target.quote, target.request, target.customerName, onClose = { sent ->
                draft = null
                if (sent) attempt++
            })
        }
    }
}

/** One draft line: a catálogo item ([itemId], its name fixed) or a typed name; quantity and unit price as typed. */
private data class DraftLine(val itemId: String?, val name: String, val quantity: String, val unitPrice: String) {
    val quantityValue: Double? get() = BusinessText.quantityInput(quantity)
    val cents: Long? get() = BusinessText.centavos(unitPrice)
    val valid: Boolean get() = name.isNotBlank() && quantityValue != null && cents != null

    /** The preview total; the server calculates the real one. */
    val previewCents: Long get() = Math.round((quantityValue ?: 0.0) * (cents ?: 0L))

    fun input(): BipeQuoteLineInput =
        if (itemId != null) BipeQuoteLineInput(itemId = itemId, quantity = quantityValue ?: 1.0, unitAmountCents = cents ?: 0)
        else BipeQuoteLineInput(name = name.trim(), quantity = quantityValue ?: 1.0, unitAmountCents = cents ?: 0)
}

/** A catálogo item's default unit price in centavos: its fixed amount, else the amount in its price text. */
private fun defaultCents(item: BipeCatalogItem): Long? = item.price.amountCents ?: item.price.text?.let(BusinessText::centavos)

/**
 * Spec quote-builder: lines from the catálogo (its price as the default unit price) or typed, quantity, unit price in
 * reais, the validity date (sent as the end of that local day in UTC) and an optional visit service. Answers [request]
 * with the first version, or sends a new version of [quote]. [onClose] says whether it went.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuoteBuilderScreen(
    business: BusinessApi,
    quote: BipeQuote?,
    request: BipeQuoteRequest?,
    customerName: String,
    onClose: (Boolean) -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<BipeCatalogItem>>(emptyList()) }
    val current = quote?.current
    val lines = remember {
        mutableStateListOf<DraftLine>().apply {
            current?.lines?.forEach { line ->
                add(DraftLine(line.itemId, line.name, BusinessText.quantity(line.quantity), BusinessText.amountField(line.unitAmountCents)))
            }
        }
    }
    val today = LocalDate.now(ZoneId.systemDefault())
    var validUntil by remember {
        mutableStateOf(
            current?.validUntil?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() }
                ?.takeIf { it.isAfter(today) || it == today }
                ?: today.plusDays(DEFAULT_VALIDITY_DAYS),
        )
    }
    var visitServiceId by remember { mutableStateOf(current?.visitServiceId) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        catalog = runCatching { business.withToken { ownerCatalog(it) } }.getOrNull().orEmpty()
    }
    val services = catalog.filter { it.isService && it.active }
    val valid = lines.isNotEmpty() && lines.all { it.valid } && (request != null || quote != null)

    fun send() {
        saving = true
        error = null
        val input = BipeQuoteVersionInput(lines.map { it.input() }, BusinessText.endOfDay(validUntil), visitServiceId)
        scope.launch {
            try {
                business.withToken { token ->
                    if (quote != null) reviseQuote(quote.id, input, token) else answerQuoteRequest(request!!.id, input, token)
                }
                onClose(true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BusinessText.error(context, failure, R.string.bipe_quote_error)
            }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.QUOTE_BUILDER.id)) {
        GroupTopBar(
            stringResource(if (quote != null) R.string.bipe_quote_new_version else R.string.bipe_quote_builder_title),
            onBack = { onClose(false) }.takeUnless { saving },
            backIcon = PhosphorIcon.X,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (customerName.isNotBlank()) Text(customerName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            request?.let { RequestedAt(it) }
            lines.forEachIndexed { index, line ->
                Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (line.itemId != null) {
                                Text(line.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                            } else {
                                OutlinedTextField(
                                    value = line.name,
                                    onValueChange = { lines[index] = line.copy(name = it.take(LINE_NAME_MAX)) },
                                    label = { Text(stringResource(R.string.bipe_quote_line_name)) },
                                    singleLine = true,
                                    enabled = !saving,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IconButton(onClick = { lines.removeAt(index) }, enabled = !saving) {
                                Icon(painterResource(PhosphorIcon.TRASH.drawable), contentDescription = stringResource(R.string.bipe_catalog_remove),
                                    tint = colors.trustDangerInk)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = line.quantity,
                                onValueChange = { lines[index] = line.copy(quantity = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(9)) },
                                label = { Text(stringResource(R.string.bipe_quote_quantity)) },
                                singleLine = true,
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = line.unitPrice,
                                onValueChange = { lines[index] = line.copy(unitPrice = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(16)) },
                                label = { Text(stringResource(R.string.bipe_quote_unit_price)) },
                                supportingText = line.cents?.let { value -> @Composable { Text(BusinessText.reais(value)) } },
                                singleLine = true,
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !saving && lines.size < MAX_LINES, onClick = { lines.add(DraftLine(null, "", "1", "")) }) {
                    Icon(painterResource(PhosphorIcon.PLUS.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.bipe_quote_add_line))
                }
            }
            val pickable = catalog.filter { it.active }
            if (pickable.isNotEmpty() && lines.size < MAX_LINES) {
                PickerField(stringResource(R.string.bipe_quote_from_catalog), stringResource(R.string.bipe_quote_add_line),
                    pickable.map { it.id to it.name }, enabled = !saving) { id ->
                    val item = pickable.firstOrNull { it.id == id } ?: return@PickerField
                    lines.add(DraftLine(item.id, item.name, "1", defaultCents(item)?.let(BusinessText::amountField).orEmpty()))
                }
            }
            PickerField(
                stringResource(R.string.bipe_quote_validity),
                BusinessText.shortDate(validUntil),
                (0..VALIDITY_DAYS).map { today.plusDays(it) }.map { it to BusinessText.shortDate(it) },
                enabled = !saving,
            ) { validUntil = it }
            if (services.isNotEmpty() || visitServiceId != null) {
                val none = stringResource(R.string.bipe_quote_visit_none)
                PickerField<String?>(
                    stringResource(R.string.bipe_quote_visit),
                    visitServiceId?.let { id -> catalog.firstOrNull { it.id == id }?.name } ?: none,
                    listOf<Pair<String?, String>>(null to none) + services.map { it.id to it.name },
                    enabled = !saving,
                ) { visitServiceId = it }
            }
            if (lines.isNotEmpty()) {
                Text(
                    stringResource(R.string.bipe_quote_total) + " · " + BusinessText.reais(lines.sumOf { it.previewCents }),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
            }
            error?.let { Text(it, color = colors.trustDangerInk) }
            BipeButton(onClick = ::send, enabled = !saving && valid, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (saving) CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
                else Text(stringResource(if (quote != null) R.string.bipe_quote_send_revision else R.string.bipe_quote_send))
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}
