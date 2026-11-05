// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeToasts
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeBusinessToday
import chat.bipe.shared.api.BipeBusinessTool
import chat.bipe.shared.api.BipeBookingStatus
import chat.bipe.shared.api.BipeOrderStatus
import chat.bipe.shared.api.BipeOwnerBookingAction
import chat.bipe.shared.api.BipeOwnerOrderAction
import chat.bipe.shared.api.BipeSeatList
import chat.bipe.shared.api.BipeWebSettings
import chat.bipe.shared.api.BipeWorkSetup
import chat.bipe.shared.api.BipeWorkSuggestion
import chat.bipe.shared.api.businessToday
import chat.bipe.shared.api.businessTools
import chat.bipe.shared.api.callNextInQueue
import chat.bipe.shared.api.dismissWorkSuggestion
import chat.bipe.shared.api.ownerBookingAction
import chat.bipe.shared.api.ownerOrderAction
import chat.bipe.shared.api.seats as readSeats
import chat.bipe.shared.api.setBusinessTool
import chat.bipe.shared.api.setWorkSector
import chat.bipe.shared.api.setWebSettings
import chat.bipe.shared.api.webSettings
import chat.bipe.shared.api.workLinkOpened
import chat.bipe.shared.api.workSetup
import chat.bipe.shared.api.workSuggestions
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CancellationException

/**
 * Spec business-tools (ADR 0194): what the Trabalho tab reads. A failed read of the tools shows every tool as a row,
 * so that nothing hides (point 5). Suggestions come from records only.
 */
class WorkHomeModel(val business: BusinessApi, private val context: Context) {
    var tools by mutableStateOf<List<BipeBusinessTool>?>(null)
        private set
    var sector by mutableStateOf<String?>(null)
        private set
    var setup by mutableStateOf<BipeWorkSetup?>(null)
        private set
    var suggestion by mutableStateOf<BipeWorkSuggestion?>(null)
        private set
    var today by mutableStateOf<BipeBusinessToday?>(null)
        private set
    /** Spec company-team. null until read: the row shows no summary and still opens the screen. */
    var seats by mutableStateOf<BipeSeatList?>(null)
        private set
    var askSector by mutableStateOf(false)
    /** The owner's web switches (ADR 0195). */
    var web by mutableStateOf<BipeWebSettings?>(null)
        private set

    private val prefs get() = context.getSharedPreferences("bipe.work", Context.MODE_PRIVATE)
    private val askedKey get() = "sectorAsked.${business.session.userId}"

    private suspend fun <T> read(call: suspend () -> T): T? = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    suspend fun load() {
        read { business.withToken { businessTools(it) } }?.let {
            tools = it.tools
            sector = it.sector
        }
        setup = read { business.withToken { workSetup(it) } }
        web = read { business.withToken { webSettings(it) } }
        loadSeats()
        suggestion = read { business.withToken { workSuggestions(it) } }?.firstOrNull()
        val hasLive = tools?.any { it.state == BipeBusinessTool.LIVE } == true
        today = if (hasLive) read { business.withToken { businessToday(it) } } else null
        // Spec work-sector: once, on a new work account. Pular and a pick both mark it.
        if (setup != null && sector == null && setup?.doneCount == 0 && !prefs.getBoolean(askedKey, false)) askSector = true
    }

    suspend fun loadSeats() {
        read { business.withToken { readSeats(it) } }?.let { seats = it }
    }

    suspend fun seatAction(call: suspend () -> BipeSeatList) {
        val updated = read { call() }
        if (updated == null) BipeToasts.show(context.getString(R.string.bipe_team_error)) else seats = updated
    }

    suspend fun saveSector(id: String?) {
        prefs.edit().putBoolean(askedKey, true).apply()
        askSector = false
        read { business.withToken { setWorkSector(id, it) } }?.let {
            tools = it.tools
            sector = it.sector
        }
        setup = read { business.withToken { workSetup(it) } }
    }

    suspend fun setState(tool: BipeBusinessTool, state: String) {
        val updated = read { business.withToken { setBusinessTool(tool.id, state, it) } }
        if (updated == null) BipeToasts.show(context.getString(R.string.bipe_work_tool_error))
        load()
    }

    suspend fun saveWeb(next: BipeWebSettings) {
        val saved = read { business.withToken { setWebSettings(next, it) } }
        if (saved == null) BipeToasts.show(context.getString(R.string.bipe_work_tool_error)) else web = saved
    }

    suspend fun dismissSuggestion() {
        val current = suggestion ?: return
        suggestion = read { business.withToken { dismissWorkSuggestion(current.id, it) } }?.firstOrNull()
    }

    suspend fun act(call: suspend () -> Unit) {
        try {
            call()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            BipeToasts.show(context.getString(R.string.bipe_error_generic))
        }
        load()
    }

    /** The link step: shares the public link and records it, the done rule of the step. */
    suspend fun shareLink() {
        val handle = read { business.withOpenId { myHandle(it) } }?.handle
        val link = handle?.let { BipeProfileLink.url(it) }
        if (link != null) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.toString())
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        read { business.withToken { workLinkOpened(it) } }
        load()
    }
}

/** Text of the registry IDs (registries/business-tools.json). */
object WorkText {
    @StringRes
    fun tool(id: String): Int = when (id) {
        "agenda" -> R.string.bipe_tab_agenda
        "catalog" -> R.string.bipe_catalog_title
        "orders" -> R.string.bipe_orders_title
        "quotes" -> R.string.bipe_quotes_title
        else -> R.string.bipe_queue_title
    }

    fun icon(id: String): PhosphorIcon = when (id) {
        "agenda" -> PhosphorIcon.CLOCK
        "catalog" -> PhosphorIcon.NOTEPAD
        "orders" -> PhosphorIcon.CHECKS
        "quotes" -> PhosphorIcon.HANDSHAKE
        else -> PhosphorIcon.USERS_THREE
    }

    @StringRes
    fun sector(id: String): Int = when (id) {
        "beauty" -> R.string.bipe_sector_beauty
        "food" -> R.string.bipe_sector_food
        "repair" -> R.string.bipe_sector_repair
        "health" -> R.string.bipe_sector_health
        "resale" -> R.string.bipe_sector_resale
        "home_services" -> R.string.bipe_sector_home_services
        else -> R.string.bipe_sector_other
    }

    @StringRes
    fun suggestion(id: String): Int = when (id) {
        "quotes_for_on_request" -> R.string.bipe_work_suggest_quotes_for_on_request
        "agenda_for_services" -> R.string.bipe_work_suggest_agenda_for_services
        else -> R.string.bipe_work_suggest_orders_for_products
    }

    @Composable
    fun stepTitle(id: String, firstTool: String): String = when (id) {
        "profile" -> stringResource(R.string.bipe_work_step_profile)
        "link" -> stringResource(R.string.bipe_work_step_link)
        "catalog" -> stringResource(R.string.bipe_work_step_catalog)
        "first_tool" -> stringResource(R.string.bipe_work_step_first_tool, stringResource(tool(firstTool)))
        "seal" -> stringResource(R.string.bipe_work_step_seal)
        else -> stringResource(R.string.bipe_work_step_second_seat)
    }

    @Composable
    fun stepBody(id: String): String = when (id) {
        "profile" -> stringResource(R.string.bipe_work_step_profile_body)
        "link" -> stringResource(R.string.bipe_work_step_link_body)
        "catalog" -> stringResource(R.string.bipe_work_step_catalog_body, BipeRegistries.BUSINESS_CATALOG_MIN_ITEMS.toString())
        "first_tool" -> stringResource(R.string.bipe_work_step_first_tool_body)
        "seal" -> stringResource(R.string.bipe_work_step_seal_body)
        else -> stringResource(R.string.bipe_work_step_second_seat_body)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = BipeTheme.colors.inkSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun WorkRow(title: String, subtitle: String? = null, icon: PhosphorIcon? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = colors.inkPrimary) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text, color = colors.inkSecondary) } },
        leadingContent = icon?.let { i -> @Composable { Icon(painterResource(i.drawable), contentDescription = null, tint = colors.glyphPrimary) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** The sections of the tab: Hoje, Próximo passo, one suggestion, the tools, Todas as ferramentas, the account rows. */
@Composable
fun WorkHomeSections(
    model: WorkHomeModel,
    run: (suspend () -> Unit) -> Unit,
    openTool: (String) -> Unit,
    openSetup: () -> Unit,
    openBrandMarks: () -> Unit,
    openKit: () -> Unit,
    openTeam: () -> Unit,
) {
    var blocked by remember { mutableStateOf<BipeBusinessTool?>(null) }
    val turnOn: (BipeBusinessTool) -> Unit = { tool ->
        if (tool.missing != null) {
            blocked = tool
        } else {
            run {
                model.setState(tool, BipeBusinessTool.SETUP)
                openTool(tool.id)
            }
        }
    }
    model.today?.let { TodaySection(it, model, run, openTool) }
    val setup = model.setup
    val next = setup?.steps?.firstOrNull { !it.done }
    if (setup != null && next != null) {
        SectionTitle(stringResource(R.string.bipe_work_next_step))
        WorkRow(
            WorkText.stepTitle(next.id, setup.firstTool),
            WorkText.stepBody(next.id) + "\n" + stringResource(R.string.bipe_work_progress, setup.doneCount.toString(), setup.steps.size.toString()),
            onClick = openSetup,
        )
    }
    model.suggestion?.let { suggestion ->
        WorkRow(stringResource(WorkText.suggestion(suggestion.id)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { run { model.dismissSuggestion() } }) { Text(stringResource(R.string.bipe_work_suggest_dismiss)) }
            TextButton(onClick = { model.tools?.firstOrNull { it.id == suggestion.tool }?.let(turnOn) }) { Text(stringResource(R.string.bipe_work_tool_turn_on)) }
        }
    }
    val tools = model.tools
    val shown = tools?.filter { it.state != BipeBusinessTool.OFF }.orEmpty()
    if (shown.isNotEmpty()) {
        SectionTitle(stringResource(R.string.bipe_business_tools_title))
        shown.forEach { tool ->
            val state = if (tool.state == BipeBusinessTool.LIVE) R.string.bipe_work_tool_state_live else R.string.bipe_work_tool_state_setup
            WorkRow(stringResource(WorkText.tool(tool.id)), stringResource(state), WorkText.icon(tool.id), onClick = { openTool(tool.id) })
        }
        Text(stringResource(R.string.bipe_business_tools_footer), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
    if (tools == null) {
        // A failed read lists every tool: nothing hides (ADR 0194 point 5).
        BipeRegistries.BUSINESS_TOOL_IDS.forEach { id ->
            WorkRow(stringResource(WorkText.tool(id)), null, WorkText.icon(id), onClick = { openTool(id) })
        }
    } else {
        val off = tools.filter { it.state == BipeBusinessTool.OFF }
        if (off.isNotEmpty()) {
            SectionTitle(stringResource(R.string.bipe_work_all_tools))
            off.forEach { tool ->
                WorkRow(stringResource(WorkText.tool(tool.id)), stringResource(R.string.bipe_work_tool_turn_on), WorkText.icon(tool.id), onClick = { turnOn(tool) })
            }
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
        val people = model.seats?.activeSeats?.size
        WorkRow(
            stringResource(R.string.bipe_team_title),
            when {
                people == null -> null
                people > 1 -> stringResource(R.string.bipe_team_row_people, people.toString())
                else -> stringResource(R.string.bipe_team_row_alone)
            },
            PhosphorIcon.USERS_THREE,
            onClick = openTeam,
        )
        WorkRow(
            stringResource(R.string.bipe_work_sector_row),
            model.sector?.let { stringResource(WorkText.sector(it)) } ?: stringResource(R.string.bipe_work_sector_none),
            onClick = { model.askSector = true },
        )
        WorkRow(stringResource(R.string.bipe_brand_marks_title), null, PhosphorIcon.SEAL_CHECK, onClick = openBrandMarks)
        WorkRow(stringResource(R.string.bipe_business_kit_title), null, PhosphorIcon.QR_CODE, onClick = openKit)
    }
    blocked?.let { tool ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            text = { Text(stringResource(if (tool.missing == "catalog_product") R.string.bipe_work_tool_needs_product else R.string.bipe_work_tool_needs_service)) },
            confirmButton = {
                TextButton(onClick = {
                    blocked = null
                    openTool("catalog")
                }) { Text(stringResource(R.string.bipe_work_tool_open_catalog)) }
            },
            dismissButton = { TextButton(onClick = { blocked = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (model.askSector) WorkSectorDialog(model, run)
}

@Composable
private fun TodaySection(today: BipeBusinessToday, model: WorkHomeModel, run: (suspend () -> Unit) -> Unit, openTool: (String) -> Unit) {
    val business = model.business
    val action: @Composable (Int, suspend () -> Unit) -> Unit = { label, call ->
        TextButton(onClick = { run { model.act(call) } }) { Text(stringResource(label)) }
    }
    SectionTitle(stringResource(R.string.bipe_work_today))
    today.bookings.forEach { booking ->
        val time = BusinessText.time(java.time.Instant.parse(booking.start))
        WorkRow(if (booking.web) "$time · ${stringResource(R.string.bipe_web_record_mark)}" else time, trailing = if (booking.bookingStatus == BipeBookingStatus.REQUESTED) {
            { action(R.string.bipe_work_today_confirm) { business.withToken { ownerBookingAction(booking.id, BipeOwnerBookingAction.ACCEPT, it) } } }
        } else null)
    }
    today.orders.forEach { order ->
        val total = BusinessText.reais(order.totalAmountCents)
        WorkRow(if (order.web) "$total · ${stringResource(R.string.bipe_web_record_mark)}" else total, trailing = if (order.orderStatus == BipeOrderStatus.SENT) {
            { action(R.string.bipe_work_today_accept) { business.withToken { ownerOrderAction(order.id, BipeOwnerOrderAction.ACCEPT, it) } } }
        } else null)
    }
    if (today.queueOpen || today.queueWaiting > 0) {
        WorkRow(stringResource(R.string.bipe_work_today_queue_waiting, today.queueWaiting.toString()), trailing = if (today.queueWaiting > 0) {
            { action(R.string.bipe_work_today_call_next) { business.withToken { callNextInQueue(it) } } }
        } else null)
    }
    if (today.quoteRequests.isNotEmpty()) {
        WorkRow(stringResource(R.string.bipe_work_today_quotes), trailing = {
            TextButton(onClick = { openTool("quotes") }) { Text(stringResource(R.string.bipe_work_today_answer)) }
        })
    }
}

/** Spec work-setup: the full list of steps. The server computes each one; the owner does not tick a step by hand. */
@Composable
fun WorkSetupScreen(model: WorkHomeModel, run: (suspend () -> Unit) -> Unit, openTool: (String) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val setup = model.setup
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(BipeScreen.WORK_SETUP.id)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
            Text(stringResource(R.string.bipe_work_setup_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        }
        setup?.let { plan ->
            plan.steps.forEach { step ->
                val open: (() -> Unit)? = when (step.id) {
                    "catalog" -> ({ openTool("catalog") })
                    "first_tool" -> ({ openTool(plan.firstTool) })
                    "link" -> ({ run { model.shareLink() } })
                    // Perfil and Selo live under Você on the phone: the row says what to do.
                    else -> null
                }
                WorkRow(WorkText.stepTitle(step.id, plan.firstTool), WorkText.stepBody(step.id),
                    if (step.done) PhosphorIcon.CHECK_CIRCLE else null, onClick = open)
            }
        }
        setup?.let {
            Text(
                if (it.doneCount == it.steps.size) stringResource(R.string.bipe_work_setup_done)
                else stringResource(R.string.bipe_work_progress, it.doneCount.toString(), it.steps.size.toString()),
                style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Spec work-sector: the sector list with Outro and Pular. It never limits the tools (ADR 0194 point 9). */
@Composable
private fun WorkSectorDialog(model: WorkHomeModel, run: (suspend () -> Unit) -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(BipeScreen.WORK_SECTOR.id),
        onDismissRequest = { model.askSector = false },
        title = { Text(stringResource(R.string.bipe_work_sector_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bipe_work_sector_body), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary)
                BipeRegistries.BUSINESS_SECTOR_IDS.forEach { id ->
                    TextButton(onClick = { run { model.saveSector(id) } }) { Text(stringResource(WorkText.sector(id))) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { run { model.saveSector(null) } }) { Text(stringResource(R.string.bipe_work_sector_skip)) } },
    )
}

/** Each owner screen has Publicar (live) and Desligar (off). */
@Composable
fun WorkToolStateBar(model: WorkHomeModel, run: (suspend () -> Unit) -> Unit, id: String) {
    val tool = model.tools?.firstOrNull { it.id == id } ?: return
    if (tool.state == BipeBusinessTool.OFF) return
    val web = model.web
    if (web != null && tool.state == BipeBusinessTool.LIVE && (id == "agenda" || id == "orders" || id == "queue")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(when (id) {
                    "agenda" -> R.string.bipe_web_booking_switch
                    "orders" -> R.string.bipe_web_orders_switch
                    else -> R.string.bipe_web_queue_switch
                }),
                style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkPrimary, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = when (id) {
                    "agenda" -> web.webBooking
                    "orders" -> web.webOrders
                    else -> web.webQueue
                },
                onCheckedChange = { on ->
                    run {
                        model.saveWeb(when (id) {
                            "agenda" -> web.copy(webBooking = on)
                            "orders" -> web.copy(webOrders = on)
                            else -> web.copy(webQueue = on)
                        })
                    }
                },
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val state = if (tool.state == BipeBusinessTool.LIVE) R.string.bipe_work_tool_state_live else R.string.bipe_work_tool_state_setup
        Text(stringResource(state), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (tool.state == BipeBusinessTool.SETUP) {
            TextButton(onClick = { run { model.setState(tool, BipeBusinessTool.LIVE) } }) { Text(stringResource(R.string.bipe_work_tool_publish)) }
        }
        TextButton(onClick = { run { model.setState(tool, BipeBusinessTool.OFF) } }) { Text(stringResource(R.string.bipe_work_tool_turn_off)) }
    }
}
