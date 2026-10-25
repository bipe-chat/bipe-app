// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The business tools Fila virtual (ADR 0107), Orçamento (ADR 0106) and Pedidos (ADR 0105) of the Bipe API. Contract:
// bipe-shared/api/openapi.yaml (tags Fila virtual, Orçamento and Pedidos) and api/business-schemas.json. Same calls as
// BipeKit BipeBusinessAPI on iOS. Owner routes (/v1/me/business/...) take the company account's own token. Customer
// routes (/v1/businesses/{business}/...) take the business handle or its Matrix ID. Bipe moves no money (ADR 0012): the
// app sends the payment choice in_person only; Pix charge cards (ADR 0080) are not built.

/** The only payment choice the app sends (contract `payment`): pay at the counter or on delivery. */
const val PAYMENT_IN_PERSON = "in_person"

/** Contract fulfilment values. */
const val FULFILMENT_PICKUP = "pickup"
const val FULFILMENT_DELIVERY = "delivery"

// Fila virtual, owner

/** `GET /v1/me/business/queue`. Today's queue; the server makes it closed on first use. */
suspend fun BipeApiClient.ownerQueue(accessToken: String): BipeQueue =
    send("GET", listOf("v1", "me", "business", "queue"), accessToken = accessToken, response = BipeQueue.serializer())

/** `POST /v1/me/business/queue/open` or `/close`. Places already in the queue stay when it closes. */
suspend fun BipeApiClient.setQueueOpen(open: Boolean, accessToken: String): BipeQueue = send(
    "POST",
    listOf("v1", "me", "business", "queue", if (open) "open" else "close"),
    accessToken = accessToken,
    response = BipeQueue.serializer(),
)

/** `PUT /v1/me/business/queue/policy`. [noShowAfterMinutes] is 1 to 240. */
suspend fun BipeApiClient.setQueuePolicy(noShowAfterMinutes: Int, accessToken: String): BipeQueue = send(
    "PUT",
    listOf("v1", "me", "business", "queue", "policy"),
    body = encode(BipeQueuePolicy.serializer(), BipeQueuePolicy(noShowAfterMinutes)),
    accessToken = accessToken,
    response = BipeQueue.serializer(),
)

/** `GET /v1/me/business/queue/places`. Today's places in order of arrival. */
suspend fun BipeApiClient.ownerQueuePlaces(accessToken: String): List<BipeQueuePlace> =
    send("GET", listOf("v1", "me", "business", "queue", "places"), accessToken = accessToken, response = QueuePlaceList.serializer()).places

/** `POST /v1/me/business/queue/places`: a walk-in, a name only or a signed-in person. */
suspend fun BipeApiClient.addQueuePlace(customer: BipeBookingCustomer, serviceId: String?, accessToken: String): BipeQueuePlace = send(
    "POST",
    listOf("v1", "me", "business", "queue", "places"),
    body = encode(OwnerQueuePlace.serializer(), OwnerQueuePlace(customer, serviceId)),
    accessToken = accessToken,
    response = BipeQueuePlace.serializer(),
)

/** `POST /v1/me/business/queue/call-next`. not_found (reason queue_empty) when nobody waits. */
suspend fun BipeApiClient.callNextInQueue(accessToken: String): BipeQueuePlace =
    send("POST", listOf("v1", "me", "business", "queue", "call-next"), accessToken = accessToken, response = BipeQueuePlace.serializer())

/** `POST /v1/me/business/queue/places/{id}/{action}`: served, no-show or recall. place_not_active otherwise. */
suspend fun BipeApiClient.queuePlaceAction(placeId: String, action: BipeQueuePlaceAction, accessToken: String): BipeQueuePlace = send(
    "POST",
    listOf("v1", "me", "business", "queue", "places", placeId, action.id),
    accessToken = accessToken,
    response = BipeQueuePlace.serializer(),
)

// Fila virtual, customer

/** `GET /v1/businesses/{business}/queue`. No auth. */
suspend fun BipeApiClient.publicQueue(business: String): BipeQueue =
    send("GET", listOf("v1", "businesses", business, "queue"), response = BipeQueue.serializer())

/** `POST /v1/businesses/{business}/queue/join`. queue_closed, already_in_queue. */
suspend fun BipeApiClient.joinQueue(business: String, serviceId: String?, accessToken: String): BipeQueuePosition = send(
    "POST",
    listOf("v1", "businesses", business, "queue", "join"),
    body = encode(QueueJoin.serializer(), QueueJoin(serviceId)),
    accessToken = accessToken,
    response = BipeQueuePosition.serializer(),
)

/** `GET /v1/businesses/{business}/queue/me`. `null` when the caller has no active place (not_found). */
suspend fun BipeApiClient.myQueuePlace(business: String, accessToken: String): BipeQueuePosition? = try {
    send("GET", listOf("v1", "businesses", business, "queue", "me"), accessToken = accessToken, response = BipeQueuePosition.serializer())
} catch (error: BipeApiException.Server) {
    if (error.code == BipeApiErrorCode.NOT_FOUND) null else throw error
}

/** `POST /v1/businesses/{business}/queue/places/{id}/leave`. place_not_active when it already ended. */
suspend fun BipeApiClient.leaveQueue(business: String, placeId: String, accessToken: String): BipeQueuePosition = send(
    "POST",
    listOf("v1", "businesses", business, "queue", "places", placeId, "leave"),
    accessToken = accessToken,
    response = BipeQueuePosition.serializer(),
)

/** `GET /v1/me/queue-places`. Active places: a person's own, or a company's as the business. */
suspend fun BipeApiClient.myQueuePlaces(accessToken: String): List<BipeQueuePosition> =
    send("GET", listOf("v1", "me", "queue-places"), accessToken = accessToken, response = QueuePositionList.serializer()).places

// Orçamento, owner

/** `GET /v1/me/business/quotes/requests`. Requests with no quote yet, newest first. */
suspend fun BipeApiClient.ownerQuoteRequests(accessToken: String): List<BipeQuoteRequest> = send(
    "GET",
    listOf("v1", "me", "business", "quotes", "requests"),
    accessToken = accessToken,
    response = QuoteRequestList.serializer(),
).requests

/** `POST /v1/me/business/quotes/requests/{id}/quote`. The first version. quote_not_open when already answered. */
suspend fun BipeApiClient.answerQuoteRequest(requestId: String, version: BipeQuoteVersionInput, accessToken: String): BipeQuote = send(
    "POST",
    listOf("v1", "me", "business", "quotes", "requests", requestId, "quote"),
    body = encode(BipeQuoteVersionInput.serializer(), version),
    accessToken = accessToken,
    response = BipeQuote.serializer(),
)

/** `GET /v1/me/business/quotes`. Most recently changed first. */
suspend fun BipeApiClient.ownerQuotes(accessToken: String): List<BipeQuote> =
    send("GET", listOf("v1", "me", "business", "quotes"), accessToken = accessToken, response = QuoteList.serializer()).quotes

/** `POST /v1/me/business/quotes/{id}/versions`. A new version of an offered (or expired) quote. */
suspend fun BipeApiClient.reviseQuote(quoteId: String, version: BipeQuoteVersionInput, accessToken: String): BipeQuote = send(
    "POST",
    listOf("v1", "me", "business", "quotes", quoteId, "versions"),
    body = encode(BipeQuoteVersionInput.serializer(), version),
    accessToken = accessToken,
    response = BipeQuote.serializer(),
)

// Orçamento, customer

/** `POST /v1/businesses/{business}/quotes/requests`. A second tap returns the open request. */
suspend fun BipeApiClient.requestQuote(business: String, accessToken: String): BipeQuoteRequest = send(
    "POST",
    listOf("v1", "businesses", business, "quotes", "requests"),
    accessToken = accessToken,
    response = BipeQuoteRequest.serializer(),
)

/** `POST /v1/businesses/{business}/quotes/{id}/accept` with target order. Payment is always in_person. */
suspend fun BipeApiClient.acceptQuoteForOrder(business: String, quoteId: String, version: Int, fulfilment: String, accessToken: String): BipeQuote = send(
    "POST",
    listOf("v1", "businesses", business, "quotes", quoteId, "accept"),
    body = encode(QuoteAcceptOrder.serializer(), QuoteAcceptOrder(version, "order", fulfilment, PAYMENT_IN_PERSON)),
    accessToken = accessToken,
    response = BipeQuote.serializer(),
)

/** `POST /v1/businesses/{business}/quotes/{id}/accept` with target booking: the server books the time with the accept. */
suspend fun BipeApiClient.acceptQuoteForBooking(
    business: String,
    quoteId: String,
    version: Int,
    serviceId: String,
    agendaId: String,
    start: String,
    accessToken: String,
): BipeQuote = send(
    "POST",
    listOf("v1", "businesses", business, "quotes", quoteId, "accept"),
    body = encode(QuoteAcceptBooking.serializer(), QuoteAcceptBooking(version, "booking", QuoteBookingSlot(serviceId, agendaId, start))),
    accessToken = accessToken,
    response = BipeQuote.serializer(),
)

/** `POST /v1/businesses/{business}/quotes/{id}/refuse` with the current version. */
suspend fun BipeApiClient.refuseQuote(business: String, quoteId: String, version: Int, accessToken: String): BipeQuote = send(
    "POST",
    listOf("v1", "businesses", business, "quotes", quoteId, "refuse"),
    body = encode(QuoteDecline.serializer(), QuoteDecline(version)),
    accessToken = accessToken,
    response = BipeQuote.serializer(),
)

/** `GET /v1/me/quotes`. Open quotes and those changed in the last 30 days: a person's, or a company's. */
suspend fun BipeApiClient.myQuotes(accessToken: String): List<BipeQuote> =
    send("GET", listOf("v1", "me", "quotes"), accessToken = accessToken, response = QuoteList.serializer()).quotes

// Pedidos, owner

/** `GET /v1/me/business/orders`. Newest first. */
suspend fun BipeApiClient.ownerOrders(accessToken: String): List<BipeOrder> =
    send("GET", listOf("v1", "me", "business", "orders"), accessToken = accessToken, response = OrderList.serializer()).orders

/** `POST /v1/me/business/orders`: an order the owner types in, accepted at once. Payment is always in_person. */
suspend fun BipeApiClient.ownerCreateOrder(
    lines: List<BipeOrderLineInput>,
    fulfilment: String,
    customer: BipeBookingCustomer,
    accessToken: String,
): BipeOrder = send(
    "POST",
    listOf("v1", "me", "business", "orders"),
    body = encode(OwnerOrderInput.serializer(), OwnerOrderInput(lines, fulfilment, PAYMENT_IN_PERSON, customer)),
    accessToken = accessToken,
    response = BipeOrder.serializer(),
)

/** `GET /v1/me/business/orders/policy`. Default: every day until 23:59. */
suspend fun BipeApiClient.orderPolicy(accessToken: String): BipeOrderPolicy =
    send("GET", listOf("v1", "me", "business", "orders", "policy"), accessToken = accessToken, response = BipeOrderPolicy.serializer())

/** `PUT /v1/me/business/orders/policy`. An empty weekday list closes orders. */
suspend fun BipeApiClient.setOrderPolicy(policy: BipeOrderPolicy, accessToken: String): BipeOrderPolicy = send(
    "PUT",
    listOf("v1", "me", "business", "orders", "policy"),
    body = encode(BipeOrderPolicy.serializer(), policy),
    accessToken = accessToken,
    response = BipeOrderPolicy.serializer(),
)

/** `POST /v1/me/business/orders/{id}/{action}`: accept, ready, done or cancel. order_not_open otherwise. */
suspend fun BipeApiClient.ownerOrderAction(orderId: String, action: BipeOwnerOrderAction, accessToken: String): BipeOrder = send(
    "POST",
    listOf("v1", "me", "business", "orders", orderId, action.id),
    accessToken = accessToken,
    response = BipeOrder.serializer(),
)

/** `POST /v1/me/business/orders/{id}/refuse` with one of the fixed reasons. */
suspend fun BipeApiClient.refuseOrder(orderId: String, reason: BipeOrderRefuseReason, accessToken: String): BipeOrder = send(
    "POST",
    listOf("v1", "me", "business", "orders", orderId, "refuse"),
    body = encode(OrderRefusal.serializer(), OrderRefusal(reason.id)),
    accessToken = accessToken,
    response = BipeOrder.serializer(),
)

// Pedidos, customer

/**
 * `POST /v1/businesses/{business}/orders`. Payment is always in_person. order_already_open, orders_closed,
 * item_unavailable, price_not_fixed.
 */
suspend fun BipeApiClient.placeOrder(business: String, lines: List<BipeOrderLineInput>, fulfilment: String, accessToken: String): BipeOrder = send(
    "POST",
    listOf("v1", "businesses", business, "orders"),
    body = encode(OrderInput.serializer(), OrderInput(lines, fulfilment, PAYMENT_IN_PERSON)),
    accessToken = accessToken,
    response = BipeOrder.serializer(),
)

/** `POST /v1/businesses/{business}/orders/{id}/cancel`. */
suspend fun BipeApiClient.cancelOrder(business: String, orderId: String, accessToken: String): BipeOrder = send(
    "POST",
    listOf("v1", "businesses", business, "orders", orderId, "cancel"),
    accessToken = accessToken,
    response = BipeOrder.serializer(),
)

/** `GET /v1/me/orders`. Open orders and the last 30 days, newest first: a person's, or a company's. */
suspend fun BipeApiClient.myOrders(accessToken: String): List<BipeOrder> =
    send("GET", listOf("v1", "me", "orders"), accessToken = accessToken, response = OrderList.serializer()).orders

// Models

/** Contract `QueuePolicy`. */
@Serializable
data class BipeQueuePolicy(@SerialName("no_show_after_minutes") val noShowAfterMinutes: Int)

/** Contract `Queue`: one per business per local day. */
@Serializable
data class BipeQueue(
    @SerialName("id") val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("date") val date: String,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("open") val open: Boolean,
    @SerialName("policy") val policy: BipeQueuePolicy,
)

/** Contract `QueuePlace`. Times are UTC instants (`...Z`). No location, no notes (ADR 0107). */
@Serializable
data class BipeQueuePlace(
    @SerialName("id") val id: String,
    @SerialName("queue_id") val queueId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer") val customer: BipeBookingCustomer,
    @SerialName("source") val source: String,
    @SerialName("service_id") val serviceId: String? = null,
    @SerialName("status") val status: String,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("called_at") val calledAt: String? = null,
    @SerialName("served_at") val servedAt: String? = null,
) {
    val placeStatus: BipeQueuePlaceStatus get() = BipeQueuePlaceStatus.fromId(status)
}

/** Contract `QueuePlaceStatus`. An unknown value reads as [LEFT], so nothing acts on it. */
enum class BipeQueuePlaceStatus(val id: String) {
    WAITING("waiting"),
    CALLED("called"),
    SERVED("served"),
    NO_SHOW("no_show"),
    LEFT("left"),
    ;

    /** waiting and called: the place still holds a turn. */
    val isActive: Boolean get() = this == WAITING || this == CALLED

    companion object {
        fun fromId(id: String): BipeQueuePlaceStatus = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/** The owner's actions on a place, as their route segment. */
enum class BipeQueuePlaceAction(val id: String) {
    SERVED("served"),
    NO_SHOW("no-show"),
    RECALL("recall"),
}

/** Contract `QueueWaitRange`: an estimate in minutes, never an exact time. */
@Serializable
data class BipeQueueWait(
    @SerialName("min_minutes") val minMinutes: Int,
    @SerialName("max_minutes") val maxMinutes: Int,
)

/** Contract `QueuePosition`: 0 when called, from 1 while waiting. No [wait] without data. */
@Serializable
data class BipeQueuePosition(
    @SerialName("place") val place: BipeQueuePlace,
    @SerialName("position") val position: Int,
    @SerialName("wait") val wait: BipeQueueWait? = null,
)

/** Contract `QuoteRequest`: only the fact of a request (ADR 0106). */
@Serializable
data class BipeQuoteRequest(
    @SerialName("id") val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("created_at") val createdAt: String,
)

/** Contract `QuoteLineInput`: a catálogo item ([itemId]) or a typed [name], never both. */
@Serializable
data class BipeQuoteLineInput(
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("quantity") val quantity: Double,
    @SerialName("unit_amount_cents") val unitAmountCents: Long,
)

/** Contract `QuoteVersionInput`. [validUntil] is a UTC instant (`...Z`). */
@Serializable
data class BipeQuoteVersionInput(
    @SerialName("lines") val lines: List<BipeQuoteLineInput>,
    @SerialName("valid_until") val validUntil: String,
    @SerialName("visit_service_id") val visitServiceId: String? = null,
)

/** Contract `QuoteLine` and `OrderLine`: a snapshot the server calculated. */
@Serializable
data class BipeCommerceLine(
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("name") val name: String,
    @SerialName("quantity") val quantity: Double,
    @SerialName("unit_amount_cents") val unitAmountCents: Long,
    @SerialName("total_amount_cents") val totalAmountCents: Long,
)

/** Contract `QuoteVersion`: immutable; older versions stay readable. */
@Serializable
data class BipeQuoteVersion(
    @SerialName("version") val version: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("valid_until") val validUntil: String,
    @SerialName("lines") val lines: List<BipeCommerceLine>,
    @SerialName("total_amount_cents") val totalAmountCents: Long,
    @SerialName("currency") val currency: String,
    @SerialName("visit_service_id") val visitServiceId: String? = null,
)

/** Contract `Quote`. Never infer paid from a quote. */
@Serializable
data class BipeQuote(
    @SerialName("id") val id: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("status") val status: String,
    @SerialName("current_version") val currentVersion: Int,
    @SerialName("versions") val versions: List<BipeQuoteVersion>,
    @SerialName("booking_id") val bookingId: String? = null,
    @SerialName("order_id") val orderId: String? = null,
) {
    val quoteStatus: BipeQuoteStatus get() = BipeQuoteStatus.fromId(status)

    /** The version that can be accepted. */
    val current: BipeQuoteVersion? get() = versions.firstOrNull { it.version == currentVersion } ?: versions.maxByOrNull { it.version }
}

/** Contract quote status. An unknown value reads as [EXPIRED], so nothing acts on it. */
enum class BipeQuoteStatus(val id: String) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    REFUSED("refused"),
    EXPIRED("expired"),
    ;

    companion object {
        fun fromId(id: String): BipeQuoteStatus = entries.firstOrNull { it.id == id } ?: EXPIRED
    }
}

/** Contract `OrderLineInput`. */
@Serializable
data class BipeOrderLineInput(
    @SerialName("item_id") val itemId: String,
    @SerialName("quantity") val quantity: Int,
)

/** Contract `Order`: no address, no notes (ADR 0105). A payment choice never means paid. */
@Serializable
data class BipeOrder(
    @SerialName("id") val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer") val customer: BipeBookingCustomer,
    @SerialName("source") val source: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("lines") val lines: List<BipeCommerceLine>,
    @SerialName("total_amount_cents") val totalAmountCents: Long,
    @SerialName("currency") val currency: String,
    @SerialName("fulfilment") val fulfilment: String,
    @SerialName("payment") val payment: String,
    @SerialName("status") val status: String,
    @SerialName("refuse_reason") val refuseReason: String? = null,
    @SerialName("quote_id") val quoteId: String? = null,
) {
    val orderStatus: BipeOrderStatus get() = BipeOrderStatus.fromId(status)
    val reason: BipeOrderRefuseReason? get() = refuseReason?.let(BipeOrderRefuseReason::fromId)
}

/** Contract `OrderStatus`. An unknown value reads as [CANCELLED], so nothing acts on it. */
enum class BipeOrderStatus(val id: String) {
    SENT("sent"),
    ACCEPTED("accepted"),
    REFUSED("refused"),
    READY("ready"),
    DONE("done"),
    CANCELLED("cancelled"),
    ;

    /** sent, accepted and ready: one open order per customer per business. */
    val isOpen: Boolean get() = this == SENT || this == ACCEPTED || this == READY

    companion object {
        fun fromId(id: String): BipeOrderStatus = entries.firstOrNull { it.id == id } ?: CANCELLED
    }
}

/** Contract `OrderRefuseReason`: fixed reasons only; free text goes in the chat. */
enum class BipeOrderRefuseReason(val id: String) {
    UNAVAILABLE("unavailable"),
    CLOSED("closed"),
    CANNOT_DELIVER("cannot_deliver"),
    CANNOT_FULFIL("cannot_fulfil"),
    ;

    companion object {
        fun fromId(id: String): BipeOrderRefuseReason? = entries.firstOrNull { it.id == id }
    }
}

/** The owner's order actions with no body, as their route segment. */
enum class BipeOwnerOrderAction(val id: String) {
    ACCEPT("accept"),
    READY("ready"),
    DONE("done"),
    CANCEL("cancel"),
}

/** Contract `OrderPolicy`. [weekdays] are ISO (Monday 1 to Sunday 7); [cutoff] is `HH:MM` local. */
@Serializable
data class BipeOrderPolicy(
    @SerialName("time_zone") val timeZone: String,
    @SerialName("weekdays") val weekdays: List<Int>,
    @SerialName("cutoff") val cutoff: String,
)

@Serializable
private data class QueuePlaceList(@SerialName("places") val places: List<BipeQueuePlace>)

@Serializable
private data class QueuePositionList(@SerialName("places") val places: List<BipeQueuePosition>)

@Serializable
private data class QuoteRequestList(@SerialName("requests") val requests: List<BipeQuoteRequest>)

@Serializable
private data class QuoteList(@SerialName("quotes") val quotes: List<BipeQuote>)

@Serializable
private data class OrderList(@SerialName("orders") val orders: List<BipeOrder>)

@Serializable
private data class QueueJoin(@SerialName("service_id") val serviceId: String? = null)

@Serializable
private data class OwnerQueuePlace(
    @SerialName("customer") val customer: BipeBookingCustomer,
    @SerialName("service_id") val serviceId: String? = null,
)

@Serializable
private data class QuoteAcceptOrder(
    @SerialName("version") val version: Int,
    @SerialName("target") val target: String,
    @SerialName("fulfilment") val fulfilment: String,
    @SerialName("payment") val payment: String,
)

@Serializable
private data class QuoteBookingSlot(
    @SerialName("service_id") val serviceId: String,
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("start") val start: String,
)

@Serializable
private data class QuoteAcceptBooking(
    @SerialName("version") val version: Int,
    @SerialName("target") val target: String,
    @SerialName("booking") val booking: QuoteBookingSlot,
)

@Serializable
private data class QuoteDecline(@SerialName("version") val version: Int)

@Serializable
private data class OrderInput(
    @SerialName("lines") val lines: List<BipeOrderLineInput>,
    @SerialName("fulfilment") val fulfilment: String,
    @SerialName("payment") val payment: String,
)

@Serializable
private data class OwnerOrderInput(
    @SerialName("lines") val lines: List<BipeOrderLineInput>,
    @SerialName("fulfilment") val fulfilment: String,
    @SerialName("payment") val payment: String,
    @SerialName("customer") val customer: BipeBookingCustomer,
)

@Serializable
private data class OrderRefusal(@SerialName("reason") val reason: String)
