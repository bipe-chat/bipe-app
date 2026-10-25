// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupFooter
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.people.BipeProfilePhoto
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAgenda
import chat.bipe.shared.api.BipeCatalogItem
import chat.bipe.shared.api.BipeCatalogItemInput
import chat.bipe.shared.api.BipeCatalogPrice
import chat.bipe.shared.api.archiveCatalogItem
import chat.bipe.shared.api.createCatalogItem
import chat.bipe.shared.api.deleteCatalogItemPhoto
import chat.bipe.shared.api.myAgenda
import chat.bipe.shared.api.ownerCatalog
import chat.bipe.shared.api.publicCatalog
import chat.bipe.shared.api.reorderCatalog
import chat.bipe.shared.api.replaceCatalogItem
import chat.bipe.shared.api.setCatalogItemPhoto
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val NAME_MAX = 120
private const val DESCRIPTION_MAX = 500
private const val CATEGORY_MAX = 80

/** Items grouped by category in the business's order of first appearance; items with no category go last. */
private fun grouped(items: List<BipeCatalogItem>): List<Pair<String?, List<BipeCatalogItem>>> {
    val named = items.filter { !it.category.isNullOrBlank() }.groupBy { it.category!!.trim() }.toList()
    val rest = items.filter { it.category.isNullOrBlank() }
    return named.map { (name, list) -> name to list } + if (rest.isEmpty()) emptyList() else listOf(null to rest)
}

/**
 * Spec catalog-view: a business's catálogo for a customer, grouped by category, with an item detail. Descriptions are
 * plain text with no link detection (security rule 10). [bookableServices] are the services Agendar can book.
 */
@Composable
fun CatalogViewScreen(
    business: BusinessApi,
    route: String,
    bookableServices: Set<String>,
    onBook: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<BipeCatalogItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<BipeCatalogItem?>(null) }
    LaunchedEffect(route, attempt) {
        failed = false
        try {
            items = business.api.publicCatalog(route)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.CATALOG_VIEW.id)) {
        GroupTopBar(stringResource(R.string.bipe_catalog_title), onBack = onClose, backIcon = PhosphorIcon.X)
        val list = items
        when {
            failed -> CenteredMessage(stringResource(R.string.bipe_catalog_load_failed)) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            list.isEmpty() -> CenteredMessage(stringResource(R.string.bipe_catalog_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                grouped(list).forEach { (category, group) ->
                    item(key = "header-" + (category ?: "")) { GroupSectionHeader(category ?: context.getString(R.string.bipe_catalog_uncategorised)) }
                    items(group, key = { it.id }) { item -> CatalogRow(item, showsHidden = false) { selected = item } }
                }
            }
        }
    }
    selected?.let { item ->
        CatalogItemDetail(
            item = item,
            canBook = item.isService && item.id in bookableServices,
            onBook = {
                selected = null
                onBook(item.id)
            },
            onClose = { selected = null },
        )
    }
}

@Composable
private fun CenteredMessage(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = BipeTheme.colors.inkSecondary, textAlign = TextAlign.Center)
        action()
    }
}

/** One item: photo, name, price text and the sold-out and hidden badges. */
@Composable
private fun CatalogRow(item: BipeCatalogItem, showsHidden: Boolean, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.bipe_catalog_item_open_a11y, item.name), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogPhoto(item.photo, item.name, 56.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            Text(BusinessText.price(context, item.price), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.soldOutToday) Badge(stringResource(R.string.bipe_catalog_sold_out_today))
                if (showsHidden && !item.active) Badge(stringResource(R.string.bipe_catalog_hidden))
            }
        }
        trailing()
    }
}

@Composable
private fun Badge(text: String) {
    val colors = BipeTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.trustCautionInk,
        modifier = Modifier.background(colors.trustCautionWash, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The item detail: photo, kind, price, duration for a service and the plain-text description. */
@Composable
private fun CatalogItemDetail(item: BipeCatalogItem, canBook: Boolean, onBook: () -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.photo != null) CatalogPhoto(item.photo, item.name, 200.dp)
                Text(
                    listOfNotNull(
                        context.getString(if (item.isService) R.string.bipe_catalog_kind_service else R.string.bipe_catalog_kind_product),
                        BusinessText.price(context, item.price),
                        item.durationMinutes?.takeIf { item.isService }?.let { BusinessText.duration(context, it) },
                    ).joinToString(" · "),
                    color = colors.inkSecondary,
                )
                if (item.soldOutToday) Badge(stringResource(R.string.bipe_catalog_sold_out_today))
                // Plain Text: no link detection (security rule 10).
                item.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.inkPrimary) }
            }
        },
        confirmButton = {
            if (canBook) TextButton(onClick = onBook) { Text(stringResource(R.string.bipe_agenda_book)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Spec catalog-owner: the company's items (hidden ones too), add, edit, sold out today, reorder and remove. */
@Composable
fun CatalogOwnerScreen(business: BusinessApi, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<BipeCatalogItem>?>(null) }
    var agendas by remember { mutableStateOf<List<BipeAgenda>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var reordering by remember { mutableStateOf(false) }
    val order = remember { mutableStateListOf<BipeCatalogItem>() }
    var editing by remember { mutableStateOf<BipeCatalogItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BipeCatalogItem?>(null) }
    var working by remember { mutableStateOf(false) }
    val notice: (Int) -> Unit = { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
    LaunchedEffect(attempt) {
        failed = false
        try {
            items = business.withToken { ownerCatalog(it) }
            // The scheduling fields show only when the Agenda is on.
            agendas = runCatching { business.withToken { myAgenda(it) } }.getOrNull()?.agendas
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    BackHandler(enabled = reordering) { reordering = false }
    Column(Modifier.fillMaxSize().testTag(BipeScreen.CATALOG_OWNER.id)) {
        GroupTopBar(stringResource(R.string.bipe_catalog_title), onBack = onClose) {
            val list = items
            if (list != null && list.size > 1) {
                if (reordering) {
                    TextButton(enabled = !working, onClick = {
                        working = true
                        scope.launch {
                            runCatching { business.withToken { reorderCatalog(order.map { it.id }, it) } }
                                .onSuccess { items = it; reordering = false }
                                .onFailure { notice(R.string.bipe_catalog_save_failed) }
                            working = false
                        }
                    }) { Text(stringResource(R.string.bipe_app_save)) }
                } else {
                    TextButton(onClick = {
                        order.clear()
                        order.addAll(list)
                        reordering = true
                    }) { Text(stringResource(R.string.bipe_catalog_reorder)) }
                }
            }
        }
        val list = items
        when {
            failed -> CenteredMessage(stringResource(R.string.bipe_catalog_load_failed)) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            reordering -> LazyColumn(Modifier.fillMaxSize()) {
                items(order.toList(), key = { it.id }) { item ->
                    val index = order.indexOf(item)
                    CatalogRow(item, showsHidden = true, trailing = {
                        val label = stringResource(R.string.bipe_catalog_reorder)
                        IconButton(onClick = { order.add(index - 1, order.removeAt(index)) }, enabled = index > 0) {
                            Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = "$label: ${item.name} ↑",
                                tint = colors.glyphPrimary, modifier = Modifier.rotate(90f))
                        }
                        IconButton(onClick = { order.add(index + 1, order.removeAt(index)) }, enabled = index < order.lastIndex) {
                            Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = "$label: ${item.name} ↓",
                                tint = colors.glyphPrimary, modifier = Modifier.rotate(-90f))
                        }
                    }) {}
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "add") {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(painterResource(PhosphorIcon.PLUS.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.bipe_catalog_add_item))
                    }
                }
                if (list.isEmpty()) item(key = "empty") { GroupFooter(stringResource(R.string.bipe_catalog_owner_empty)) }
                grouped(list).forEach { (category, group) ->
                    item(key = "header-" + (category ?: "")) { GroupSectionHeader(category ?: context.getString(R.string.bipe_catalog_uncategorised)) }
                    items(group, key = { it.id }) { item ->
                        CatalogRow(item, showsHidden = true, trailing = {
                            // Action toggleSoldOut: one tap.
                            TextButton(enabled = !working, onClick = {
                                working = true
                                scope.launch {
                                    runCatching { business.withToken { replaceCatalogItem(item.id, item.input().copy(soldOutToday = !item.soldOutToday), it) } }
                                        .onSuccess { saved -> items = items?.map { if (it.id == saved.id) saved else it } }
                                        .onFailure { notice(R.string.bipe_catalog_save_failed) }
                                    working = false
                                }
                            }) {
                                Text(stringResource(if (item.soldOutToday) R.string.bipe_catalog_mark_available else R.string.bipe_catalog_mark_sold_out),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { removing = item }, enabled = !working) {
                                Icon(painterResource(PhosphorIcon.TRASH.drawable), contentDescription = stringResource(R.string.bipe_catalog_remove),
                                    tint = colors.trustDangerInk)
                            }
                        }) { editing = item }
                        HorizontalDivider(color = colors.lineSubtle)
                    }
                }
            }
        }
    }
    removing?.let { item ->
        RemoveItemDialog(onKeep = { removing = null }, onRemove = {
            removing = null
            scope.launch {
                runCatching { business.withToken { archiveCatalogItem(item.id, it) } }
                    .onSuccess {
                        items = items?.filterNot { it.id == item.id }
                        notice(R.string.bipe_catalog_removed)
                    }
                    .onFailure { notice(R.string.bipe_catalog_save_failed) }
            }
        })
    }
    if (adding || editing != null) {
        BusinessFullScreen(onDismiss = { adding = false; editing = null }) {
            CatalogItemEditScreen(
                business = business,
                item = editing,
                agendas = agendas,
                nextSort = (items.orEmpty().maxOfOrNull { it.sort } ?: -1) + 1,
                onClose = { changed ->
                    adding = false
                    editing = null
                    if (changed) attempt++
                },
            )
        }
    }
}

/** bipe_catalog_remove with its body: the item is archived, bookings and orders keep it. */
@Composable
private fun RemoveItemDialog(onKeep: () -> Unit, onRemove: () -> Unit) {
    val colors = BipeTheme.colors
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.bipe_catalog_remove)) },
        text = { Text(stringResource(R.string.bipe_catalog_remove_body), color = colors.inkSecondary) },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.bipe_catalog_remove), color = colors.trustDangerInk) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

private enum class PriceMode(val id: String, val label: Int) {
    FIXED(BipeCatalogPrice.FIXED, R.string.bipe_catalog_price_fixed),
    FROM(BipeCatalogPrice.FROM, R.string.bipe_catalog_price_from_option),
    ON_REQUEST(BipeCatalogPrice.ON_REQUEST, R.string.bipe_catalog_on_request_option),
}

/**
 * Spec catalog-item-edit: one form for a product or a service. A service also has duration, buffers and the people who
 * do it when the Agenda is on ([agendas] not `null`). Products never send scheduling fields. The price text is written
 * from the amount in reais ("R$ 40,00").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogItemEditScreen(
    business: BusinessApi,
    item: BipeCatalogItem?,
    agendas: List<BipeAgenda>?,
    nextSort: Int,
    initialKind: String = BipeCatalogItem.PRODUCT,
    onClose: (Boolean) -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(item?.kind ?: initialKind) }
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var mode by remember { mutableStateOf(PriceMode.entries.firstOrNull { it.id == item?.price?.mode } ?: PriceMode.FIXED) }
    var amount by remember {
        mutableStateOf(item?.price?.let { price -> (price.amountCents ?: price.text?.let(BusinessText::centavos))?.let(BusinessText::amountField) }.orEmpty())
    }
    var description by remember { mutableStateOf(item?.description.orEmpty()) }
    var category by remember { mutableStateOf(item?.category.orEmpty()) }
    var active by remember { mutableStateOf(item?.active ?: true) }
    var duration by remember { mutableStateOf((item?.durationMinutes ?: 30).toString()) }
    var before by remember { mutableStateOf((item?.bufferBeforeMinutes ?: 0).toString()) }
    var after by remember { mutableStateOf((item?.bufferAfterMinutes ?: 0).toString()) }
    val people = remember { mutableStateListOf<String>().apply { addAll(item?.agendaIds ?: agendas.orEmpty().map { it.id }) } }
    var photoUrl by remember { mutableStateOf(item?.photo) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Scaled down to a JPEG under the profile photo limit.
            val jpeg = BipeProfilePhoto.load(context, uri)?.let { BipeProfilePhoto.jpeg(it) }
            if (jpeg == null) error = context.getString(R.string.bipe_catalog_photo_failed) else newPhoto = jpeg
        }
    }
    val cents = BusinessText.centavos(amount)
    val isService = kind == BipeCatalogItem.SERVICE
    val schedules = isService && agendas != null
    val valid = name.isNotBlank() && (mode == PriceMode.ON_REQUEST || cents != null) &&
        (!schedules || (duration.toIntOrNull()?.let { it in 1..1440 } == true && before.toIntOrNull() != null && after.toIntOrNull() != null && people.isNotEmpty()))

    fun input(): BipeCatalogItemInput {
        val price = if (mode == PriceMode.ON_REQUEST) BipeCatalogPrice(BipeCatalogPrice.ON_REQUEST)
        else BipeCatalogPrice(mode.id, BusinessText.reais(cents ?: 0), cents)
        return BipeCatalogItemInput(
            kind = kind,
            name = name.trim(),
            price = price,
            description = description.takeIf { it.isNotBlank() },
            category = category.trim().takeIf { it.isNotEmpty() },
            active = active,
            sort = item?.sort ?: nextSort,
            soldOutToday = item?.soldOutToday ?: false,
            durationMinutes = if (schedules) duration.toInt() else if (isService) item?.durationMinutes else null,
            bufferBeforeMinutes = if (schedules) before.toInt() else if (isService) item?.bufferBeforeMinutes else null,
            bufferAfterMinutes = if (schedules) after.toInt() else if (isService) item?.bufferAfterMinutes else null,
            agendaIds = if (schedules) people.toList() else if (isService) item?.agendaIds?.takeIf { it.isNotEmpty() } else null,
        )
    }

    fun save() {
        saving = true
        error = null
        scope.launch {
            try {
                val saved = business.withToken { token ->
                    if (item == null) createCatalogItem(input(), token) else replaceCatalogItem(item.id, input(), token)
                }
                changed = true
                // The photo goes up after the item exists.
                newPhoto?.let { jpeg ->
                    runCatching { business.withToken { setCatalogItemPhoto(saved.id, jpeg, it) } }
                        .onFailure {
                            error = context.getString(R.string.bipe_catalog_photo_failed)
                            saving = false
                            return@launch
                        }
                }
                Toast.makeText(context, context.getString(R.string.bipe_catalog_saved), Toast.LENGTH_SHORT).show()
                onClose(true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = context.getString(R.string.bipe_catalog_save_failed)
            }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.CATALOG_ITEM_EDIT.id)) {
        GroupTopBar(
            stringResource(if (item == null) R.string.bipe_catalog_add_item else R.string.bipe_catalog_edit_item),
            onBack = { onClose(changed) }.takeUnless { saving },
            backIcon = PhosphorIcon.X,
        ) {
            if (saving) {
                CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
            } else {
                TextButton(onClick = ::save, enabled = valid) { Text(stringResource(R.string.bipe_catalog_save)) }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Photo.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val local = newPhoto
                if (local != null) {
                    val image = remember(local) { android.graphics.BitmapFactory.decodeByteArray(local, 0, local.size)?.asImageBitmap() }
                    if (image != null) Image(image, contentDescription = stringResource(R.string.bipe_catalog_photo), contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12)))
                } else {
                    CatalogPhoto(photoUrl, name, 72.dp)
                }
                Column {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !saving) {
                        Text(stringResource(R.string.bipe_catalog_add_photo))
                    }
                    if (newPhoto != null || photoUrl != null) {
                        TextButton(enabled = !saving, onClick = {
                            if (newPhoto != null) {
                                newPhoto = null
                            } else if (item != null) {
                                // Action removePhoto.
                                scope.launch {
                                    runCatching { business.withToken { deleteCatalogItemPhoto(item.id, it) } }
                                        .onSuccess { photoUrl = null; changed = true }
                                        .onFailure { error = context.getString(R.string.bipe_catalog_photo_failed) }
                                }
                            }
                        }) { Text(stringResource(R.string.bipe_catalog_remove_photo), color = colors.trustDangerInk) }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(NAME_MAX) },
                label = { Text(stringResource(R.string.bipe_catalog_item_name)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.bipe_catalog_item_kind), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BipeCatalogItem.SERVICE to R.string.bipe_catalog_kind_service, BipeCatalogItem.PRODUCT to R.string.bipe_catalog_kind_product).forEach { (id, label) ->
                    FilterChip(selected = kind == id, onClick = { kind = id }, enabled = !saving, label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.bipe_catalog_price), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = { mode = option }, enabled = !saving, label = { Text(stringResource(option.label)) })
                }
            }
            if (mode != PriceMode.ON_REQUEST) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(16) },
                    label = { Text(stringResource(R.string.bipe_catalog_amount)) },
                    supportingText = cents?.let { value -> @Composable { Text(BusinessText.reais(value)) } },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(DESCRIPTION_MAX) },
                label = { Text(stringResource(R.string.bipe_catalog_description)) },
                enabled = !saving,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it.take(CATEGORY_MAX) },
                label = { Text(stringResource(R.string.bipe_catalog_category)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_catalog_active), color = colors.inkPrimary, modifier = Modifier.weight(1f))
                Switch(checked = active, onCheckedChange = { active = it }, enabled = !saving)
            }
            if (schedules) {
                NumberField(stringResource(R.string.bipe_agenda_service_duration), duration, !saving) { duration = it }
                NumberField(stringResource(R.string.bipe_agenda_buffer_before), before, !saving) { before = it }
                NumberField(stringResource(R.string.bipe_agenda_buffer_after), after, !saving) { after = it }
                Text(stringResource(R.string.bipe_agenda_setup_people), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    agendas.orEmpty().forEach { agenda ->
                        FilterChip(
                            selected = agenda.id in people,
                            onClick = { if (agenda.id in people) people.remove(agenda.id) else people.add(agenda.id) },
                            enabled = !saving,
                            label = { Text(agenda.name) },
                        )
                    }
                }
            }
            error?.let { Text(it, color = colors.trustDangerInk) }
            if (item != null) {
                TextButton(onClick = { removing = true }, enabled = !saving) {
                    Text(stringResource(R.string.bipe_catalog_remove), color = colors.trustDangerInk)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (removing && item != null) {
        RemoveItemDialog(onKeep = { removing = false }, onRemove = {
            removing = false
            saving = true
            scope.launch {
                runCatching { business.withToken { archiveCatalogItem(item.id, it) } }
                    .onSuccess {
                        Toast.makeText(context, context.getString(R.string.bipe_catalog_removed), Toast.LENGTH_SHORT).show()
                        onClose(true)
                    }
                    .onFailure { error = context.getString(R.string.bipe_catalog_save_failed) }
                saving = false
            }
        })
    }
}

/** A whole-minutes field. */
@Composable
fun NumberField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
