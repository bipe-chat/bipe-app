// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Trabalho tab step by step (ADR 0194). Contract bipe-shared/api/openapi.yaml, /v1/me/business/tools, sector,
// setup, suggestions and today, and the public /v1/businesses/{business}/tools. Same calls as
// BipeBusinessSetupAPI.swift on iOS.

/** Contract `BusinessToolStatus`. [state] is off, setup or live; [missing] is catalog_service or catalog_product. */
@Serializable
data class BipeBusinessTool(
    @SerialName("id") val id: String,
    @SerialName("state") val state: String,
    @SerialName("missing") val missing: String? = null,
) {
    companion object {
        const val OFF = "off"
        const val SETUP = "setup"
        const val LIVE = "live"
    }
}

/** Contract `BusinessTools`. */
@Serializable
data class BipeBusinessTools(
    @SerialName("tools") val tools: List<BipeBusinessTool>,
    @SerialName("sector") val sector: String? = null,
)

/** Contract `WorkSetupStep`. */
@Serializable
data class BipeWorkSetupStep(@SerialName("id") val id: String, @SerialName("done") val done: Boolean)

/** Contract `WorkSetup`. */
@Serializable
data class BipeWorkSetup(
    @SerialName("steps") val steps: List<BipeWorkSetupStep>,
    @SerialName("done_count") val doneCount: Int,
    @SerialName("first_tool") val firstTool: String,
)

/** Contract `WorkSuggestion`. */
@Serializable
data class BipeWorkSuggestion(@SerialName("id") val id: String, @SerialName("tool") val tool: String)

/** Contract `BusinessToday`. */
@Serializable
data class BipeBusinessToday(
    @SerialName("bookings") val bookings: List<BipeBooking>,
    @SerialName("orders") val orders: List<BipeOrder>,
    @SerialName("queue_open") val queueOpen: Boolean,
    @SerialName("queue_waiting") val queueWaiting: Int,
    @SerialName("quote_requests") val quoteRequests: List<BipeQuoteRequest>,
)

@Serializable
private data class ToolChange(@SerialName("state") val state: String)

// explicitNulls is off in the client: a null sector is left out, which clears it.
@Serializable
private data class SectorBody(@SerialName("sector") val sector: String?)

@Serializable
private data class WorkSuggestions(@SerialName("suggestions") val suggestions: List<BipeWorkSuggestion>)

@Serializable
private data class LiveTools(@SerialName("tools") val tools: List<String>)

/** `GET /v1/me/business/tools`. */
suspend fun BipeApiClient.businessTools(accessToken: String): BipeBusinessTools =
    send("GET", listOf("v1", "me", "business", "tools"), accessToken = accessToken, response = BipeBusinessTools.serializer())

/** `PUT /v1/me/business/tools/{toolId}`. tool_needs_catalog when a dependency is missing. */
suspend fun BipeApiClient.setBusinessTool(id: String, state: String, accessToken: String): BipeBusinessTools = send(
    "PUT",
    listOf("v1", "me", "business", "tools", id),
    body = encode(ToolChange.serializer(), ToolChange(state)),
    accessToken = accessToken,
    response = BipeBusinessTools.serializer(),
)

/** `PUT /v1/me/business/sector`. null clears the sector (Pular). */
suspend fun BipeApiClient.setWorkSector(sector: String?, accessToken: String): BipeBusinessTools = send(
    "PUT",
    listOf("v1", "me", "business", "sector"),
    body = encode(SectorBody.serializer(), SectorBody(sector)),
    accessToken = accessToken,
    response = BipeBusinessTools.serializer(),
)

/** `GET /v1/me/business/setup`. */
suspend fun BipeApiClient.workSetup(accessToken: String): BipeWorkSetup =
    send("GET", listOf("v1", "me", "business", "setup"), accessToken = accessToken, response = BipeWorkSetup.serializer())

/** `POST /v1/me/business/setup/link-opened`. */
suspend fun BipeApiClient.workLinkOpened(accessToken: String): BipeWorkSetup =
    send("POST", listOf("v1", "me", "business", "setup", "link-opened"), accessToken = accessToken, response = BipeWorkSetup.serializer())

/** `GET /v1/me/business/suggestions`. */
suspend fun BipeApiClient.workSuggestions(accessToken: String): List<BipeWorkSuggestion> =
    send("GET", listOf("v1", "me", "business", "suggestions"), accessToken = accessToken, response = WorkSuggestions.serializer()).suggestions

/** `POST /v1/me/business/suggestions/{id}/dismiss`. */
suspend fun BipeApiClient.dismissWorkSuggestion(id: String, accessToken: String): List<BipeWorkSuggestion> =
    send("POST", listOf("v1", "me", "business", "suggestions", id, "dismiss"), accessToken = accessToken, response = WorkSuggestions.serializer()).suggestions

/** `GET /v1/me/business/today`. */
suspend fun BipeApiClient.businessToday(accessToken: String): BipeBusinessToday =
    send("GET", listOf("v1", "me", "business", "today"), accessToken = accessToken, response = BipeBusinessToday.serializer())

/** `GET /v1/businesses/{business}/tools`. No auth. Live tool IDs only. */
suspend fun BipeApiClient.liveTools(business: String): List<String> =
    send("GET", listOf("v1", "businesses", business, "tools"), response = LiveTools.serializer()).tools

/** Contract `WebSettings`: the owner's web switches (ADR 0195). */
@Serializable
data class BipeWebSettings(
    @SerialName("web_booking") val webBooking: Boolean,
    @SerialName("web_orders") val webOrders: Boolean,
    @SerialName("web_queue") val webQueue: Boolean,
)

/** Contract `WebRecords`: the records a claim moved to the account. */
@Serializable
data class BipeWebRecords(
    @SerialName("bookings") val bookings: List<BipeBooking>,
    @SerialName("orders") val orders: List<BipeOrder>,
)

@Serializable
private data class WebClaim(@SerialName("token") val token: String)

/** `GET /v1/me/business/web-settings`. */
suspend fun BipeApiClient.webSettings(accessToken: String): BipeWebSettings =
    send("GET", listOf("v1", "me", "business", "web-settings"), accessToken = accessToken, response = BipeWebSettings.serializer())

/** `PUT /v1/me/business/web-settings`. */
suspend fun BipeApiClient.setWebSettings(settings: BipeWebSettings, accessToken: String): BipeWebSettings = send(
    "PUT",
    listOf("v1", "me", "business", "web-settings"),
    body = encode(BipeWebSettings.serializer(), settings),
    accessToken = accessToken,
    response = BipeWebSettings.serializer(),
)

/** `POST /v1/businesses/{business}/web/claim`. The token's records move to this account; the token stops working. */
suspend fun BipeApiClient.claimWebRecords(business: String, token: String, accessToken: String): BipeWebRecords = send(
    "POST",
    listOf("v1", "businesses", business, "web", "claim"),
    body = encode(WebClaim.serializer(), WebClaim(token)),
    accessToken = accessToken,
    response = BipeWebRecords.serializer(),
)

@Serializable
private data class BusinessStop(@SerialName("stopped") val stopped: Boolean)

/** `GET /v1/business-stops/{workAccount}`: whether this account stopped the work account (ADR 0237). */
suspend fun BipeApiClient.businessStopped(workAccount: String, accessToken: String): Boolean =
    send("GET", listOf("v1", "business-stops", workAccount), accessToken = accessToken, response = BusinessStop.serializer()).stopped

/** `PUT` or `DELETE /v1/business-stops/{workAccount}`. */
suspend fun BipeApiClient.setBusinessStopped(workAccount: String, stopped: Boolean, accessToken: String): Boolean =
    send(if (stopped) "PUT" else "DELETE", listOf("v1", "business-stops", workAccount), accessToken = accessToken, response = BusinessStop.serializer()).stopped

/** Contract `AgendaFeed`: a secret URL that shows once. */
@Serializable
data class BipeAgendaFeed(
    @SerialName("url") val url: String,
    @SerialName("include_customer_first_name") val includeCustomerFirstName: Boolean,
)

/** Contract `BusyImport`. The secret address never comes back. */
@Serializable
data class BipeBusyImport(
    @SerialName("id") val id: String,
    @SerialName("agenda_id") val agendaId: String,
    @SerialName("failing") val failing: Boolean = false,
)

@Serializable
private data class FeedOptions(@SerialName("include_customer_first_name") val includeCustomerFirstName: Boolean)

@Serializable
private data class BusyImportInput(@SerialName("url") val url: String)

@Serializable
private data class BusyImports(@SerialName("imports") val imports: List<BipeBusyImport>)

/** `POST /v1/me/business/agenda/agendas/{id}/feed`. The URL shows once; a new one revokes the old (ADR 0101). */
suspend fun BipeApiClient.issueAgendaFeed(agendaId: String, includeFirstName: Boolean, accessToken: String): BipeAgendaFeed = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "agendas", agendaId, "feed"),
    body = encode(FeedOptions.serializer(), FeedOptions(includeFirstName)),
    accessToken = accessToken,
    response = BipeAgendaFeed.serializer(),
)

/** `GET .../busy-imports`. */
suspend fun BipeApiClient.busyImports(agendaId: String, accessToken: String): List<BipeBusyImport> =
    send("GET", listOf("v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports"), accessToken = accessToken, response = BusyImports.serializer()).imports

/** `POST .../busy-imports`. bad_request for an address the server refuses or cannot read. */
suspend fun BipeApiClient.addBusyImport(agendaId: String, url: String, accessToken: String): BipeBusyImport = send(
    "POST",
    listOf("v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports"),
    body = encode(BusyImportInput.serializer(), BusyImportInput(url)),
    accessToken = accessToken,
    response = BipeBusyImport.serializer(),
)

/** `DELETE .../busy-imports/{id}`, with its busy blocks. */
suspend fun BipeApiClient.removeBusyImport(agendaId: String, importId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports", importId), accessToken = accessToken, response = null)
}

// People on a work account (ADR 0251, ADR 0252). Contract /v1/me/business/seats. Same calls as BipeBusinessSetupAPI.swift.

/** Contract `SeatDevice`. [kind] is phone or web. */
@Serializable
data class BipeSeatDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("kind") val kind: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("added_at") val addedAt: String,
)

/** Contract `Seat`. */
@Serializable
data class BipeSeat(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("role") val role: String,
    @SerialName("status") val status: String,
    @SerialName("devices") val devices: List<BipeSeatDevice>,
    @SerialName("window_expires_at") val windowExpiresAt: String? = null,
)

/** Contract `SeatMe`. [role] is owner, attendant or none. A device with none has no owner rights. */
@Serializable
data class BipeSeatMe(
    @SerialName("seat_id") val seatId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("role") val role: String,
)

/** Contract `SeatList`. */
@Serializable
data class BipeSeatList(
    @SerialName("me") val me: BipeSeatMe,
    @SerialName("seats") val seats: List<BipeSeat>,
    @SerialName("unassigned_devices") val unassignedDevices: List<BipeSeatDevice>,
    @SerialName("max_people") val maxPeople: Int,
    @SerialName("backup_rotation_pending") val backupRotationPending: Boolean,
) {
    /** The people who answer now. A waiting seat is not one yet. */
    val activeSeats: List<BipeSeat> get() = seats.filter { it.status == "active" }
}

@Serializable
private data class SeatDeviceAssign(@SerialName("seat_id") val seatId: String)

/** `GET /v1/me/business/seats`. Also lets a phone that just joined take the open owner window. */
suspend fun BipeApiClient.seats(accessToken: String): BipeSeatList =
    send("GET", listOf("v1", "me", "business", "seats"), accessToken = accessToken, response = BipeSeatList.serializer())

/** `POST /v1/me/business/seats/{seatId}/window`. Only the owner's own seat. */
suspend fun BipeApiClient.openSeatWindow(seatId: String, accessToken: String): BipeSeat =
    send("POST", listOf("v1", "me", "business", "seats", seatId, "window"), accessToken = accessToken, response = BipeSeat.serializer())

/** `PUT /v1/me/business/seats/devices/{deviceId}`. A device with no person joins a seat. */
suspend fun BipeApiClient.assignSeatDevice(deviceId: String, seatId: String, accessToken: String): BipeSeatList = send(
    "PUT",
    listOf("v1", "me", "business", "seats", "devices", deviceId),
    body = encode(SeatDeviceAssign.serializer(), SeatDeviceAssign(seatId)),
    accessToken = accessToken,
    response = BipeSeatList.serializer(),
)

/** `DELETE /v1/me/business/seats/devices/{deviceId}`. Ends the session of that device. */
suspend fun BipeApiClient.removeSeatDevice(deviceId: String, accessToken: String): BipeSeatList =
    send("DELETE", listOf("v1", "me", "business", "seats", "devices", deviceId), accessToken = accessToken, response = BipeSeatList.serializer())
