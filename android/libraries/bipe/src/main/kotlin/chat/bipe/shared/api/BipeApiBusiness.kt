// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The business tools of the Bipe API: Catálogo (ADR 0104) and Agenda (ADRs 0099 to 0103). Contract:
// bipe-shared/api/openapi.yaml (tags Catálogo and Agenda) and api/business-schemas.json. Same calls as BipeKit
// BipeBusinessAPI on iOS. Owner routes (/v1/me/business/...) take the company account's own token. Customer routes
// (/v1/businesses/{business}/...) take the business handle or its Matrix ID; [business] is one path segment, so the
// client percent-encodes it. Bipe moves no money: prices are text (ADR 0012).

// Catálogo, owner

/** `GET /v1/me/business/catalog`. Hidden items included, in the business's order. */
suspend fun BipeApiClient.ownerCatalog(accessToken: String): List<BipeCatalogItem> =
    send("GET", listOf("v1", "me", "business", "catalog"), accessToken = accessToken, response = BipeCatalog.serializer()).items

/** `POST /v1/me/business/catalog/items`. */
suspend fun BipeApiClient.createCatalogItem(item: BipeCatalogItemInput, accessToken: String): BipeCatalogItem = send(
    "POST",
    listOf("v1", "me", "business", "catalog", "items"),
    body = encode(BipeCatalogItemInput.serializer(), item),
    accessToken = accessToken,
    response = BipeCatalogItem.serializer(),
)

/** `PUT /v1/me/business/catalog/items/{itemId}`. Replaces every field. */
suspend fun BipeApiClient.replaceCatalogItem(itemId: String, item: BipeCatalogItemInput, accessToken: String): BipeCatalogItem = send(
    "PUT",
    listOf("v1", "me", "business", "catalog", "items", itemId),
    body = encode(BipeCatalogItemInput.serializer(), item),
    accessToken = accessToken,
    response = BipeCatalogItem.serializer(),
)

/** `DELETE /v1/me/business/catalog/items/{itemId}`. The item is archived: bookings that name it keep it. */
suspend fun BipeApiClient.archiveCatalogItem(itemId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "business", "catalog", "items", itemId), accessToken = accessToken, response = null)
}

/** `PUT /v1/me/business/catalog/order`. The listed items take that order. */
suspend fun BipeApiClient.reorderCatalog(itemIds: List<String>, accessToken: String): List<BipeCatalogItem> = send(
    "PUT",
    listOf("v1", "me", "business", "catalog", "order"),
    body = encode(CatalogOrder.serializer(), CatalogOrder(itemIds)),
    accessToken = accessToken,
    response = BipeCatalog.serializer(),
).items

/** `PUT /v1/me/business/catalog/items/{itemId}/photo` with a JPEG under the profile photo limit. */
suspend fun BipeApiClient.setCatalogItemPhoto(itemId: String, jpeg: ByteArray, accessToken: String): BipeCatalogItem {
    val (status, text) = exchange("PUT", listOf("v1", "me", "business", "catalog", "items", itemId, "photo"), jpeg, "image/jpeg", accessToken)
    if (status !in 200..299) throw serverError(status, text)
    return decodePeople(BipeCatalogItem.serializer(), text, status)
}

/** `DELETE /v1/me/business/catalog/items/{itemId}/photo`. */
suspend fun BipeApiClient.deleteCatalogItemPhoto(itemId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "business", "catalog", "items", itemId, "photo"), accessToken = accessToken, response = null)
}

// Catálogo, customer

/** `GET /v1/businesses/{business}/catalog`. No auth. Active items only. */
suspend fun BipeApiClient.publicCatalog(business: String): List<BipeCatalogItem> =
    send("GET", listOf("v1", "businesses", business, "catalog"), response = BipeCatalog.serializer()).items

// Agenda, owner

/** `GET /v1/me/business/agenda`. `null` before activation (not_found with reason agenda_off). */
suspend fun BipeApiClient.myAgenda(accessToken: String): BipeAgendaState? = try {
    send("GET", listOf("v1", "me", "business", "agenda"), accessToken = accessToken, response = BipeAgendaState.serializer())
} catch (error: BipeApiException.Server) {
    if (error.code == BipeApiErrorCode.NOT_FOUND && error.reason == AGENDA_OFF) null else throw error
}

/** `PUT /v1/me/business/agenda`. Publishing fails with bad_request `incomplete` without a bookable service. */
suspend fun BipeApiClient.setAgendaSettings(published: Boolean, timeZone: String, accessToken: String): BipeAgendaState = send(
    "PUT",
    listOf("v1", "me", "business", "agenda"),
    body = encode(AgendaSettings.serializer(), AgendaSettings(published, timeZone)),
    accessToken = accessToken,
    response = BipeAgendaState.serializer(),
)

/** `POST /v1/me/business/agenda/activate`. The server makes the first agenda and the template's services. */
suspend fun BipeApiClient.activateAgenda(templateId: String, timeZone: String, accessToken: String): BipeAgendaState = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "activate"),
    body = encode(AgendaActivation.serializer(), AgendaActivation(templateId, timeZone)),
    accessToken = accessToken,
    response = BipeAgendaState.serializer(),
)

/** `POST /v1/me/business/agenda/agendas`. A new agenda starts with the first one's hours. */
suspend fun BipeApiClient.createAgenda(agenda: BipeAgendaInput, accessToken: String): BipeAgenda = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "agendas"),
    body = encode(BipeAgendaInput.serializer(), agenda),
    accessToken = accessToken,
    response = BipeAgenda.serializer(),
)

/** `PUT /v1/me/business/agenda/agendas/{agendaId}`. */
suspend fun BipeApiClient.replaceAgenda(agendaId: String, agenda: BipeAgendaInput, accessToken: String): BipeAgenda = send(
    "PUT",
    listOf("v1", "me", "business", "agenda", "agendas", agendaId),
    body = encode(BipeAgendaInput.serializer(), agenda),
    accessToken = accessToken,
    response = BipeAgenda.serializer(),
)

/** `DELETE /v1/me/business/agenda/agendas/{agendaId}`. Bookings keep it. */
suspend fun BipeApiClient.deleteAgenda(agendaId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "business", "agenda", "agendas", agendaId), accessToken = accessToken, response = null)
}

/** `GET /v1/me/business/agenda/agendas/{agendaId}/availability`. Local wall times in the business time zone. */
suspend fun BipeApiClient.agendaAvailability(agendaId: String, accessToken: String): List<BipeAvailability> = send(
    "GET",
    listOf("v1", "me", "business", "agenda", "agendas", agendaId, "availability"),
    accessToken = accessToken,
    response = AvailabilityRules.serializer(),
).rules

/** `PUT /v1/me/business/agenda/agendas/{agendaId}/availability`. Ranges of one day must not overlap. */
suspend fun BipeApiClient.setAgendaAvailability(agendaId: String, rules: List<BipeAvailability>, accessToken: String): List<BipeAvailability> = send(
    "PUT",
    listOf("v1", "me", "business", "agenda", "agendas", agendaId, "availability"),
    body = encode(AvailabilityRules.serializer(), AvailabilityRules(rules)),
    accessToken = accessToken,
    response = AvailabilityRules.serializer(),
).rules

/** `POST /v1/me/business/agenda/blocks`. [start] and [end] are UTC instants (`...Z`). */
suspend fun BipeApiClient.blockAgendas(agendaIds: List<String>, start: String, end: String, accessToken: String) {
    send<Unit>(
        "POST",
        listOf("v1", "me", "business", "agenda", "blocks"),
        body = encode(AgendaBlock.serializer(), AgendaBlock(agendaIds, start, end)),
        accessToken = accessToken,
        response = null,
    )
}

/** `POST /v1/me/business/agenda/close-day`. [date] is a local date (`YYYY-MM-DD`) in the business time zone. */
suspend fun BipeApiClient.closeAgendaDay(agendaIds: List<String>, date: String, accessToken: String) {
    send<Unit>(
        "POST",
        listOf("v1", "me", "business", "agenda", "close-day"),
        body = encode(AgendaCloseDay.serializer(), AgendaCloseDay(agendaIds, date)),
        accessToken = accessToken,
        response = null,
    )
}

/** `PUT /v1/me/business/agenda/policy`. Every field; send reminder_minutes as read. */
suspend fun BipeApiClient.setAgendaPolicy(policy: BipeAgendaPolicy, accessToken: String): BipeAgendaPolicy = send(
    "PUT",
    listOf("v1", "me", "business", "agenda", "policy"),
    body = encode(BipeAgendaPolicy.serializer(), policy),
    accessToken = accessToken,
    response = BipeAgendaPolicy.serializer(),
)

/** `GET /v1/me/business/agenda/bookings`. Local dates in the business time zone; every status but expired holds. */
suspend fun BipeApiClient.ownerBookings(dateFrom: String, dateTo: String, agendaId: String?, accessToken: String): List<BipeBooking> = send(
    "GET",
    listOf("v1", "me", "business", "agenda", "bookings"),
    accessToken = accessToken,
    response = BookingList.serializer(),
    query = listOfNotNull("date_from" to dateFrom, "date_to" to dateTo, agendaId?.let { "agenda_id" to it }),
).bookings

/** `POST /v1/me/business/agenda/bookings`: an encaixe (source owner). Only an overlap is refused (slot_taken). */
suspend fun BipeApiClient.ownerBook(
    serviceId: String,
    agendaId: String,
    start: String,
    customer: BipeBookingCustomer,
    accessToken: String,
): BipeBooking = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "bookings"),
    body = encode(OwnerBooking.serializer(), OwnerBooking(serviceId, agendaId, start, customer)),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `POST /v1/me/business/agenda/bookings/{id}/move`. */
suspend fun BipeApiClient.ownerMoveBooking(bookingId: String, agendaId: String, start: String, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "bookings", bookingId, "move"),
    body = encode(BookingMove.serializer(), BookingMove(agendaId, start)),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `POST /v1/me/business/agenda/bookings/{id}/{action}`: cancel, no-show, accept or refuse. */
suspend fun BipeApiClient.ownerBookingAction(bookingId: String, action: BipeOwnerBookingAction, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "bookings", bookingId, action.id),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

// Agenda, customer

/** `GET /v1/businesses/{business}/agenda/services`. No auth. agenda_not_published when the agenda is off. */
suspend fun BipeApiClient.publicServices(business: String): List<BipeCatalogItem> =
    send("GET", listOf("v1", "businesses", business, "agenda", "services"), response = ServiceList.serializer()).services

/** `GET /v1/businesses/{business}/agenda/agendas`, only those that do [serviceId] when given. No auth. */
suspend fun BipeApiClient.publicAgendas(business: String, serviceId: String?): List<BipeAgenda> = send(
    "GET",
    listOf("v1", "businesses", business, "agenda", "agendas"),
    response = AgendaList.serializer(),
    query = listOfNotNull(serviceId?.let { "service_id" to it }),
).agendas

/** `GET /v1/businesses/{business}/agenda/slots`. No auth. [agendaId] is an agenda ID or [ANY_AGENDA]; under 31 days. */
suspend fun BipeApiClient.agendaSlots(business: String, serviceId: String, agendaId: String, dateFrom: String, dateTo: String): BipeSlotDays = send(
    "GET",
    listOf("v1", "businesses", business, "agenda", "slots"),
    response = BipeSlotDays.serializer(),
    query = listOf("service_id" to serviceId, "agenda_id" to agendaId, "date_from" to dateFrom, "date_to" to dateTo),
)

/** `POST /v1/businesses/{business}/agenda/hold`. Holds the time for 5 minutes. */
suspend fun BipeApiClient.holdBooking(business: String, serviceId: String, agendaId: String, start: String, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "businesses", business, "agenda", "hold"),
    body = encode(BookingHold.serializer(), BookingHold(serviceId, agendaId, start)),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `POST /v1/businesses/{business}/agenda/bookings/{id}/confirm`. hold_expired when the 5 minutes passed. */
suspend fun BipeApiClient.confirmBooking(business: String, bookingId: String, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "businesses", business, "agenda", "bookings", bookingId, "confirm"),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `POST /v1/businesses/{business}/agenda/bookings/{id}/cancel`. cancellation_window_closed inside the window. */
suspend fun BipeApiClient.cancelBooking(business: String, bookingId: String, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "businesses", business, "agenda", "bookings", bookingId, "cancel"),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `POST /v1/businesses/{business}/agenda/bookings/{id}/move`. */
suspend fun BipeApiClient.moveBooking(business: String, bookingId: String, agendaId: String, start: String, accessToken: String): BipeBooking = send(
    "POST",
    listOf("v1", "businesses", business, "agenda", "bookings", bookingId, "move"),
    body = encode(BookingMove.serializer(), BookingMove(agendaId, start)),
    accessToken = accessToken,
    response = BipeBooking.serializer(),
)

/** `GET /v1/me/bookings`. Open bookings: a person's own, or a company's as the business. Soonest first. */
suspend fun BipeApiClient.myBookings(accessToken: String): List<BipeBooking> =
    send("GET", listOf("v1", "me", "bookings"), accessToken = accessToken, response = BookingList.serializer()).bookings

/** `reason` of not_found on owner Agenda routes before activation. */
const val AGENDA_OFF = "agenda_off"

/** The agenda ID that lets the server pick a free person (Qualquer pessoa). */
const val ANY_AGENDA = "any"

/** `reason` of bad_request when publishing without a bookable service. */
const val AGENDA_INCOMPLETE = "incomplete"

/** Contract `CatalogPrice`: fixed and from carry the business's text (and, optionally, centavos); on request has neither. */
@Serializable
data class BipeCatalogPrice(
    @SerialName("mode") val mode: String,
    @SerialName("text") val text: String? = null,
    @SerialName("amount_cents") val amountCents: Long? = null,
) {
    companion object {
        const val FIXED = "fixed"
        const val FROM = "from"
        const val ON_REQUEST = "on_request"
    }
}

/** Contract `CatalogItem`. A service also carries the Agenda fields. [photo] is a public URL. */
@Serializable
data class BipeCatalogItem(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("name") val name: String,
    @SerialName("price") val price: BipeCatalogPrice,
    @SerialName("description") val description: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("active") val active: Boolean,
    @SerialName("sort") val sort: Int,
    @SerialName("sold_out_today") val soldOutToday: Boolean = false,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("buffer_before_minutes") val bufferBeforeMinutes: Int? = null,
    @SerialName("buffer_after_minutes") val bufferAfterMinutes: Int? = null,
    @SerialName("agenda_ids") val agendaIds: List<String>? = null,
    @SerialName("photo") val photo: String? = null,
) {
    val isService: Boolean get() = kind == SERVICE

    /** The fields to send back with a change; the photo has its own route. */
    fun input(): BipeCatalogItemInput = BipeCatalogItemInput(
        kind = kind, name = name, price = price, description = description, category = category, active = active, sort = sort,
        soldOutToday = soldOutToday, durationMinutes = durationMinutes, bufferBeforeMinutes = bufferBeforeMinutes,
        bufferAfterMinutes = bufferAfterMinutes, agendaIds = agendaIds?.takeIf { it.isNotEmpty() },
    )

    companion object {
        const val SERVICE = "service"
        const val PRODUCT = "product"
    }
}

/** Contract `CatalogItemInput`. A product sends none of the scheduling fields (they stay `null` and are left out). */
@Serializable
data class BipeCatalogItemInput(
    @SerialName("kind") val kind: String,
    @SerialName("name") val name: String,
    @SerialName("price") val price: BipeCatalogPrice,
    @SerialName("description") val description: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("active") val active: Boolean,
    @SerialName("sort") val sort: Int,
    @SerialName("sold_out_today") val soldOutToday: Boolean = false,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("buffer_before_minutes") val bufferBeforeMinutes: Int? = null,
    @SerialName("buffer_after_minutes") val bufferAfterMinutes: Int? = null,
    @SerialName("agenda_ids") val agendaIds: List<String>? = null,
)

/** Contract `Agenda`: a person or a station. The person needs no Bipe account (ADR 0099). */
@Serializable
data class BipeAgenda(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("photo") val photo: String? = null,
    @SerialName("active") val active: Boolean,
    @SerialName("sort") val sort: Int,
)

/** Contract `AgendaInput`. */
@Serializable
data class BipeAgendaInput(
    @SerialName("name") val name: String,
    @SerialName("active") val active: Boolean,
    @SerialName("sort") val sort: Int,
)

/** Contract `Availability`: one range of a weekday (ISO, Monday 1 to Sunday 7), `HH:MM` local wall times. */
@Serializable
data class BipeAvailability(
    @SerialName("weekday") val weekday: Int,
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
)

/** Contract `Policy`. */
@Serializable
data class BipeAgendaPolicy(
    @SerialName("min_notice_minutes") val minNoticeMinutes: Int,
    @SerialName("horizon_days") val horizonDays: Int,
    @SerialName("slot_step_minutes") val slotStepMinutes: Int,
    @SerialName("cancel_window_minutes") val cancelWindowMinutes: Int,
    @SerialName("confirm_mode") val confirmMode: String,
    @SerialName("max_open_bookings_per_customer") val maxOpenBookingsPerCustomer: Int,
    @SerialName("reminder_minutes") val reminderMinutes: List<Int>,
) {
    val requestMode: Boolean get() = confirmMode == REQUEST

    companion object {
        const val AUTO = "auto"
        const val REQUEST = "request"
    }
}

/** Contract `AgendaState`: the owner's whole Agenda. [services] are catalogue items of kind service. */
@Serializable
data class BipeAgendaState(
    @SerialName("published") val published: Boolean,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("agendas") val agendas: List<BipeAgenda>,
    @SerialName("services") val services: List<BipeCatalogItem>,
    @SerialName("policy") val policy: BipeAgendaPolicy,
)

/** Contract `BookingCustomer`: a signed-in customer ([userId]) or a name only (an encaixe). */
@Serializable
data class BipeBookingCustomer(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
) {
    override fun toString(): String = if (userId != null) "BipeBookingCustomer($userId)" else "BipeBookingCustomer(name)"
}

/** Contract `Booking`. Times are UTC instants (`...Z`). Bookings never carry notes (ADR 0102). */
@Serializable
data class BipeBooking(
    @SerialName("id") val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("service_id") val serviceId: String,
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
    @SerialName("status") val status: String,
    @SerialName("customer") val customer: BipeBookingCustomer,
    @SerialName("source") val source: String,
    @SerialName("hold_expires_at") val holdExpiresAt: String? = null,
) {
    val bookingStatus: BipeBookingStatus get() = BipeBookingStatus.fromId(status)
}

/** Contract `BookingStatus`. An unknown value reads as [CANCELLED], so nothing acts on it. */
enum class BipeBookingStatus(val id: String) {
    HELD("held"),
    REQUESTED("requested"),
    CONFIRMED("confirmed"),
    DONE("done"),
    CANCELLED("cancelled"),
    NO_SHOW("no_show"),
    ;

    /** held, requested and confirmed: the booking still holds its time. */
    val isOpen: Boolean get() = this == HELD || this == REQUESTED || this == CONFIRMED

    companion object {
        fun fromId(id: String): BipeBookingStatus = entries.firstOrNull { it.id == id } ?: CANCELLED
    }
}

/** The owner's actions on a booking, as their route segment. */
enum class BipeOwnerBookingAction(val id: String) {
    CANCEL("cancel"),
    NO_SHOW("no-show"),
    ACCEPT("accept"),
    REFUSE("refuse"),
}

/** Contract `Slot`. */
@Serializable
data class BipeSlot(
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
)

/** Contract `SlotDay`: a local date in the business time zone and its free starts. */
@Serializable
data class BipeSlotDay(
    @SerialName("date") val date: String,
    @SerialName("slots") val slots: List<BipeSlot>,
)

/** Contract `SlotDays`. */
@Serializable
data class BipeSlotDays(
    @SerialName("time_zone") val timeZone: String,
    @SerialName("days") val days: List<BipeSlotDay>,
)

@Serializable
private data class BipeCatalog(@SerialName("items") val items: List<BipeCatalogItem>)

@Serializable
private data class CatalogOrder(@SerialName("item_ids") val itemIds: List<String>)

@Serializable
private data class ServiceList(@SerialName("services") val services: List<BipeCatalogItem>)

@Serializable
private data class AgendaList(@SerialName("agendas") val agendas: List<BipeAgenda>)

@Serializable
private data class BookingList(@SerialName("bookings") val bookings: List<BipeBooking>)

@Serializable
private data class AvailabilityRules(@SerialName("rules") val rules: List<BipeAvailability>)

@Serializable
private data class AgendaSettings(
    @SerialName("published") val published: Boolean,
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
private data class AgendaActivation(
    @SerialName("template_id") val templateId: String,
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
private data class AgendaBlock(
    @SerialName("agenda_ids") val agendaIds: List<String>,
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
)

@Serializable
private data class AgendaCloseDay(
    @SerialName("agenda_ids") val agendaIds: List<String>,
    @SerialName("date") val date: String,
)

@Serializable
private data class OwnerBooking(
    @SerialName("service_id") val serviceId: String,
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("start") val start: String,
    @SerialName("customer") val customer: BipeBookingCustomer,
)

@Serializable
private data class BookingHold(
    @SerialName("service_id") val serviceId: String,
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("start") val start: String,
)

@Serializable
private data class BookingMove(
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("start") val start: String,
)
