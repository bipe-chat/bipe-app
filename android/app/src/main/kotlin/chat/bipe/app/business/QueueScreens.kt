// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.people.QrCode
import chat.bipe.app.people.qrBitmap
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeBookingCustomer
import chat.bipe.shared.api.BipeQueue
import chat.bipe.shared.api.BipeQueuePlace
import chat.bipe.shared.api.BipeQueuePlaceAction
import chat.bipe.shared.api.BipeQueuePlaceStatus
import chat.bipe.shared.api.BipeQueuePosition
import chat.bipe.shared.api.BipeQueueWait
import chat.bipe.shared.api.addQueuePlace
import chat.bipe.shared.api.callNextInQueue
import chat.bipe.shared.api.joinQueue
import chat.bipe.shared.api.leaveQueue
import chat.bipe.shared.api.myQueuePlace
import chat.bipe.shared.api.ownerQueue
import chat.bipe.shared.api.ownerQueuePlaces
import chat.bipe.shared.api.publicQueue
import chat.bipe.shared.api.queuePlaceAction
import chat.bipe.shared.api.setQueueOpen
import chat.bipe.shared.api.setQueuePolicy
import chat.bipe.shared.generated.BipeScreen
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spec queue-owner setWindow: 1 to 240 minutes. */
private const val WINDOW_MAX_MINUTES = 240

/** The query item of the counter QR link, https://bipe.chat/@<handle>?fila (spec queue-customer notes). */
const val QUEUE_LINK_QUERY = "fila"

/** The counter QR link of a business handle. */
fun queueLink(handle: String): String? = BipeProfileLink.url(handle)?.let { "$it?$QUEUE_LINK_QUERY" }

/** bipe_queue_wait with the range, or bipe_queue_wait_unknown: never zero, never an exact time (spec queue-customer). */
@Composable
private fun waitText(wait: BipeQueueWait?): String =
    if (wait == null) stringResource(R.string.bipe_queue_wait_unknown)
    else stringResource(R.string.bipe_queue_wait, stringResource(R.string.bipe_queue_wait_range, wait.minMinutes.toString(), wait.maxMinutes.toString()))

/**
 * The queue card in the chat (spec business-chat-bar cards, queue-customer): the position or bipe_queue_called, and the
 * wait range. On the company side ([customerName] set) it shows the customer's place. A tap opens the queue.
 */
@Composable
fun QueueCard(position: BipeQueuePosition, businessName: String, customerName: String?, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    val status = position.place.placeStatus
    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag(BipeScreen.QUEUE_CUSTOMER.id).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.USERS_THREE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.bipe_queue_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentInk,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(BusinessText.queueStatus(status)), style = MaterialTheme.typography.labelMedium, color = queueStatusColor(status))
            }
            if (customerName != null) {
                Text(customerName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            } else if (businessName.isNotBlank()) {
                Text(businessName, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            if (status == BipeQueuePlaceStatus.CALLED) {
                Text(stringResource(R.string.bipe_queue_called), style = MaterialTheme.typography.titleMedium, color = colors.trustVerifiedInk)
            } else if (position.position > 0) {
                Text(stringResource(R.string.bipe_queue_position_number, position.position.toString()), style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary)
                Text(waitText(position.wait), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
    }
}

@Composable
private fun queueStatusColor(status: BipeQueuePlaceStatus) = when (status) {
    BipeQueuePlaceStatus.CALLED, BipeQueuePlaceStatus.SERVED -> BipeTheme.colors.trustVerifiedInk
    BipeQueuePlaceStatus.WAITING -> BipeTheme.colors.trustCautionInk
    BipeQueuePlaceStatus.NO_SHOW, BipeQueuePlaceStatus.LEFT -> BipeTheme.colors.inkSecondary
}

/**
 * Spec queue-customer: today's queue of [route] (handle or Matrix ID), join, the position and a wait range, and leave
 * with a confirm. Reads again every 30 seconds while the place is active. Bipe never asks for the location (ADR 0107).
 */
@Composable
fun QueueCustomerScreen(business: BusinessApi, route: String, businessName: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<BipeQueue?>(null) }
    var place by remember { mutableStateOf<BipeQueuePosition?>(null) }
    var name by remember { mutableStateOf(businessName) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val foreground = rememberForegroundRevision()

    suspend fun load() {
        try {
            val read = business.api.publicQueue(route)
            queue = read
            place = business.withToken { myQueuePlace(route, it) }?.takeIf { it.place.placeStatus.isActive }
            if (name.isBlank()) name = business.displayName(read.businessId)
            failed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (queue == null) failed = true
        }
    }

    LaunchedEffect(route, attempt, foreground) { load() }
    val active = place != null
    LaunchedEffect(route, active) {
        while (active) {
            delay(BusinessChatTools.QUEUE_REFRESH_MS)
            load()
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.QUEUE_CUSTOMER.id)) {
        GroupTopBar(stringResource(R.string.bipe_queue_title), onBack = onClose, backIcon = PhosphorIcon.X)
        val current = queue
        when {
            failed && current == null -> LoadFailed(stringResource(R.string.bipe_queue_load_failed)) { attempt++ }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
                val mine = place
                if (mine != null) {
                    if (mine.place.placeStatus == BipeQueuePlaceStatus.CALLED) {
                        Text(stringResource(R.string.bipe_queue_called), style = MaterialTheme.typography.headlineMedium, color = colors.trustVerifiedInk,
                            textAlign = TextAlign.Center)
                    } else {
                        Text(stringResource(R.string.bipe_queue_position), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                        Text(stringResource(R.string.bipe_queue_position_number, mine.position.toString()), style = MaterialTheme.typography.headlineMedium,
                            color = colors.inkPrimary, textAlign = TextAlign.Center)
                        Text(waitText(mine.wait), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.bipe_queue_estimate_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary,
                            textAlign = TextAlign.Center)
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, textAlign = TextAlign.Center) }
                    error?.let { Text(it, color = colors.trustDangerInk, textAlign = TextAlign.Center) }
                    BipeOutlinedButton(enabled = !working, onClick = { confirmLeave = true }) {
                        Text(stringResource(R.string.bipe_queue_leave), color = colors.trustDangerInk)
                    }
                } else if (current.open) {
                    error?.let { Text(it, color = colors.trustDangerInk, textAlign = TextAlign.Center) }
                    BipeButton(enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = {
                        working = true
                        error = null
                        scope.launch {
                            try {
                                place = business.withToken { joinQueue(route, null, it) }
                                notice = context.getString(R.string.bipe_queue_joined)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                // already_in_queue and queue_closed have their own text (BusinessText.error).
                                error = BusinessText.error(context, failure, R.string.bipe_queue_error)
                                load()
                            }
                            working = false
                        }
                    }) {
                        if (working) CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
                        else Text(stringResource(R.string.bipe_queue_join))
                    }
                } else {
                    Text(stringResource(R.string.bipe_queue_closed), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    error?.let { Text(it, color = colors.trustDangerInk, textAlign = TextAlign.Center) }
                }
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.bipe_queue_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    val mine = place ?: return@TextButton
                    working = true
                    error = null
                    scope.launch {
                        runCatching { business.withToken { leaveQueue(route, mine.place.id, it) } }
                            .onSuccess {
                                place = null
                                notice = null
                            }
                            .onFailure { error = BusinessText.error(context, it, R.string.bipe_queue_error) }
                        load()
                        working = false
                    }
                }) { Text(stringResource(R.string.bipe_queue_leave), color = colors.trustDangerInk) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
            containerColor = colors.surfaceRaised,
        )
    }
}

/** What queue-owner has open. */
private sealed interface QueueOwnerSheet {
    data object WalkIn : QueueOwnerSheet
    data object Settings : QueueOwnerSheet
    data class Qr(val link: String) : QueueOwnerSheet
}

/** Waiting and called first (called on top, then by arrival), then the rest of today by arrival. */
private fun ordered(places: List<BipeQueuePlace>): List<BipeQueuePlace> =
    places.sortedWith(
        compareBy<BipeQueuePlace>(
            { if (it.placeStatus.isActive) 0 else 1 },
            { if (it.placeStatus == BipeQueuePlaceStatus.CALLED) 0 else 1 },
            { it.joinedAt },
        ),
    )

/**
 * Spec queue-owner: open and close today's queue, the waiting count, Chamar próximo, the places with Atendido, Não veio
 * and Chamar de novo, a walk-in (a name or a person from [customers], the company's DMs), the arrival window and the
 * counter QR code. Reads again every 30 seconds while it shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueOwnerScreen(business: BusinessApi, customers: List<Pair<String, String>>, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<BipeQueue?>(null) }
    var places by remember { mutableStateOf<List<BipeQueuePlace>>(emptyList()) }
    val names = remember { mutableStateMapOf<String, String>() }
    var handle by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<QueueOwnerSheet?>(null) }
    val foreground = rememberForegroundRevision()

    suspend fun load() {
        try {
            val read = business.withToken { ownerQueue(it) }
            places = ordered(business.withToken { ownerQueuePlaces(it) })
            queue = read
            places.mapNotNull { it.customer.userId }.distinct().filter { it !in names }.forEach { names[it] = business.displayName(it) }
            failed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (queue == null) failed = true
        }
    }

    fun perform(call: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                call()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BusinessText.error(context, failure, R.string.bipe_queue_error)
            }
            load()
            working = false
        }
    }

    LaunchedEffect(attempt, foreground) {
        load()
        if (handle == null) handle = runCatching { business.withToken { business.api.myHandle(it) } }.getOrNull()?.handle
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BusinessChatTools.QUEUE_REFRESH_MS)
            load()
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.QUEUE_OWNER.id)) {
        GroupTopBar(stringResource(R.string.bipe_queue_title), onBack = onClose) {
            val link = handle?.let(::queueLink)
            if (link != null) {
                IconButton(onClick = { sheet = QueueOwnerSheet.Qr(link) }) {
                    Icon(painterResource(PhosphorIcon.QR_CODE.drawable), contentDescription = stringResource(R.string.bipe_queue_qr_title), tint = colors.glyphPrimary)
                }
            }
            IconButton(onClick = { sheet = QueueOwnerSheet.Settings }, enabled = queue != null) {
                Icon(painterResource(PhosphorIcon.GEAR.drawable), contentDescription = stringResource(R.string.bipe_queue_settings), tint = colors.glyphPrimary)
            }
        }
        val current = queue
        when {
            failed && current == null -> LoadFailed(stringResource(R.string.bipe_queue_load_failed)) { attempt++ }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            else -> {
                val waiting = places.count { it.placeStatus == BipeQueuePlaceStatus.WAITING }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (current.open) R.string.bipe_queue_open_now else R.string.bipe_queue_closed),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (current.open) colors.trustVerifiedInk else colors.inkSecondary,
                            )
                            Text(stringResource(R.string.bipe_queue_waiting_count, waiting.toString()), style = MaterialTheme.typography.bodyMedium,
                                color = colors.inkSecondary)
                        }
                        BipeOutlinedButton(enabled = !working, onClick = { perform { business.withToken { setQueueOpen(!current.open, it) } } }) {
                            Text(stringResource(if (current.open) R.string.bipe_queue_close else R.string.bipe_queue_open))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Chamar próximo is hidden when nobody waits.
                        if (waiting > 0) {
                            BipeButton(enabled = !working, onClick = { perform { business.withToken { callNextInQueue(it) } } }) {
                                Text(stringResource(R.string.bipe_queue_call_next))
                            }
                        }
                        BipeOutlinedButton(enabled = !working, onClick = { sheet = QueueOwnerSheet.WalkIn }) { Text(stringResource(R.string.bipe_queue_add_walk_in)) }
                    }
                    error?.let { Text(it, color = colors.trustDangerInk) }
                }
                if (places.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.bipe_queue_owner_empty), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(places, key = { it.id }) { place ->
                            PlaceRow(
                                place = place,
                                name = place.customer.userId?.let { names[it] } ?: place.customer.name.orEmpty(),
                                enabled = !working,
                                onAction = { action -> perform { business.withToken { queuePlaceAction(place.id, action, it) } } },
                            )
                        }
                    }
                }
            }
        }
    }
    when (val open = sheet) {
        null -> Unit
        QueueOwnerSheet.WalkIn -> WalkInDialog(customers, onClose = { sheet = null }) { customer ->
            sheet = null
            perform { business.withToken { addQueuePlace(customer, null, it) } }
        }
        QueueOwnerSheet.Settings -> WindowDialog(queue?.policy?.noShowAfterMinutes ?: 10, onClose = { sheet = null }) { minutes ->
            sheet = null
            perform { queue = business.withToken { setQueuePolicy(minutes, it) } }
        }
        is QueueOwnerSheet.Qr -> QrDialog(open.link, onClose = { sheet = null })
    }
}

/** One place: name, arrival time, status and the actions its status allows (the server's rules). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceRow(place: BipeQueuePlace, name: String, enabled: Boolean, onAction: (BipeQueuePlaceAction) -> Unit) {
    val colors = BipeTheme.colors
    val status = place.placeStatus
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            Text(stringResource(BusinessText.queueStatus(status)), style = MaterialTheme.typography.labelMedium, color = queueStatusColor(status))
        }
        runCatching { Instant.parse(place.joinedAt) }.getOrNull()?.let {
            Text(BusinessText.time(it), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status == BipeQueuePlaceStatus.WAITING || status == BipeQueuePlaceStatus.CALLED || status == BipeQueuePlaceStatus.NO_SHOW) {
                TextButton(enabled = enabled, onClick = { onAction(BipeQueuePlaceAction.SERVED) }) { Text(stringResource(R.string.bipe_queue_mark_served)) }
            }
            if (status == BipeQueuePlaceStatus.WAITING || status == BipeQueuePlaceStatus.CALLED) {
                TextButton(enabled = enabled, onClick = { onAction(BipeQueuePlaceAction.NO_SHOW) }) { Text(stringResource(R.string.bipe_queue_mark_no_show)) }
            }
            if (status == BipeQueuePlaceStatus.CALLED || status == BipeQueuePlaceStatus.NO_SHOW) {
                TextButton(enabled = enabled, onClick = { onAction(BipeQueuePlaceAction.RECALL) }) { Text(stringResource(R.string.bipe_queue_recall)) }
            }
        }
    }
    HorizontalDivider(color = colors.lineSubtle)
}

/** Spec queue-owner addWalkIn: a name, or a person from the company's chats. */
@Composable
private fun WalkInDialog(customers: List<Pair<String, String>>, onClose: () -> Unit, onAdd: (BipeBookingCustomer) -> Unit) {
    val colors = BipeTheme.colors
    var customer by remember { mutableStateOf<BipeBookingCustomer?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bipe_queue_add_walk_in)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { CustomerChoice(customers, enabled = true) { customer = it } } },
        confirmButton = {
            TextButton(enabled = customer != null, onClick = { customer?.let(onAdd) }) { Text(stringResource(R.string.bipe_app_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Spec queue-owner setWindow: the minutes to arrive after a call, 1 to 240. */
@Composable
private fun WindowDialog(minutes: Int, onClose: () -> Unit, onSave: (Int) -> Unit) {
    val colors = BipeTheme.colors
    var text by remember { mutableStateOf(minutes.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 1..WINDOW_MAX_MINUTES }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bipe_queue_settings)) },
        text = { NumberField(stringResource(R.string.bipe_queue_no_show_window), text, enabled = true) { text = it.take(3) } },
        confirmButton = { TextButton(enabled = value != null, onClick = { value?.let(onSave) }) { Text(stringResource(R.string.bipe_app_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Spec queue-owner showQr: the counter QR code of [link] with bipe_queue_qr_body, to show or share as an image to print. */
@Composable
private fun QrDialog(link: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bipe_queue_qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QrCode(link)
                Text(stringResource(R.string.bipe_queue_qr_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (!shareQr(context, link)) Toast.makeText(context, context.getString(R.string.bipe_queue_error), Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.bipe_app_share)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Pixels per QR module and the quiet zone in modules of the shared image. */
private const val SHARE_SCALE = 16
private const val SHARE_MARGIN_MODULES = 4

/** Writes the QR code of [link] as a PNG in the cache and opens the share sheet. `false` when it could not. */
private suspend fun shareQr(context: Context, link: String): Boolean {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val modules = qrBitmap(link) ?: return@runCatching null
            val scaled = Bitmap.createScaledBitmap(modules, modules.width * SHARE_SCALE, modules.height * SHARE_SCALE, false)
            val margin = SHARE_MARGIN_MODULES * SHARE_SCALE
            val image = Bitmap.createBitmap(scaled.width + 2 * margin, scaled.height + 2 * margin, Bitmap.Config.ARGB_8888)
            // Scanners need dark modules on a light ground (as QrCode does); no token is fixed light.
            Canvas(image).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(scaled, margin.toFloat(), margin.toFloat(), null)
            }
            val dir = File(context.cacheDir, "queue").apply { mkdirs() }
            File(dir, "bipe-fila-qr.png").also { out -> FileOutputStream(out).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }.getOrNull()
    } ?: return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(Intent.createChooser(send, null)) }.isSuccess
}

/**
 * Spec queue-customer from the counter QR link https://bipe.chat/@<handle>?fila: queue-customer for that handle, in
 * the people stack. The customer routes take the handle as is.
 */
@Composable
fun QueueLinkScreen(session: AccountSession, handle: String, onClose: () -> Unit) {
    val business = remember(session) { BusinessApi(session) }
    Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            QueueCustomerScreen(business, route = handle, businessName = "", onClose = onClose)
        }
    }
}
