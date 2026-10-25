// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBookingStatus
import chat.bipe.shared.api.BipeCatalogPrice
import chat.bipe.shared.api.BipeCommerceLine
import chat.bipe.shared.api.BipeOrderRefuseReason
import chat.bipe.shared.api.BipeOrderStatus
import chat.bipe.shared.api.BipeQueuePlaceStatus
import chat.bipe.shared.api.BipeQuoteStatus
import chat.bipe.shared.api.FULFILMENT_DELIVERY
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared parts of the business tools (Agenda and Catálogo): the API with the account's token, price and time text,
// error text, the calendar file, photos from bipe-api and the full-screen frame the flows open in.

/** The Bipe API of the business tools for one account. Owner routes use this account's own token. */
class BusinessApi(val session: AccountSession) {
    val api = BipeApiClient(AppConfig.API_BASE_URL)

    /** Runs [request] with the account's token, retrying once on 401 (the SDK may have refreshed it). */
    suspend fun <T> withToken(request: suspend BipeApiClient.(String) -> T): T =
        BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.request(token) }

    /** A display name of a Matrix account: the profile name, else the ID's localpart. Never the full Matrix ID. */
    suspend fun displayName(userId: String): String {
        names[userId]?.let { return it }
        val name = runCatching { session.client.getProfile(userId).displayName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: userId.removePrefix("@").substringBefore(':')
        names[userId] = name
        return name
    }

    private val names = mutableMapOf<String, String>()
}

/** Price, money and time text of the business specs. */
object BusinessText {
    private val brazil = Locale.forLanguageTag("pt-BR")

    /** Spec catalog-view priceText: fixed shows the business's text, from adds "a partir de", on request its own word. */
    fun price(context: Context, price: BipeCatalogPrice): String = when (price.mode) {
        BipeCatalogPrice.FROM -> context.getString(R.string.bipe_catalog_price_from, price.text.orEmpty())
        BipeCatalogPrice.ON_REQUEST -> context.getString(R.string.bipe_catalog_price_on_request)
        else -> price.text.orEmpty()
    }

    /** Centavos as Brazilian reais, "R$ 40,00" (spec catalog-item-edit: the app writes the text in BRL). */
    fun reais(cents: Long): String {
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(brazil))
        return "R$ " + format.format(BigDecimal.valueOf(cents).movePointLeft(2))
    }

    /** Reais typed by the owner ("40", "40,5", "1.234,50") as centavos; `null` when it is not an amount. */
    fun centavos(text: String): Long? {
        val clean = text.trim().removePrefix("R$").trim().replace(".", "").replace(',', '.')
        if (clean.isEmpty() || !clean.matches(Regex("\\d{1,12}(\\.\\d{0,2})?"))) return null
        return runCatching { BigDecimal(clean).setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact() }.getOrNull()
    }

    /** Centavos as the amount field shows them, "40,00". */
    fun amountField(cents: Long): String =
        DecimalFormat("0.00", DecimalFormatSymbols(brazil)).format(BigDecimal.valueOf(cents).movePointLeft(2))

    fun duration(context: Context, minutes: Int): String = context.getString(R.string.bipe_agenda_duration, minutes.toString())

    /** A span of minutes in words, "2 horas", for the cancel policy line. */
    fun span(minutes: Int): String {
        val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        val measure = when {
            minutes > 0 && minutes % 1440 == 0 -> Measure(minutes / 1440, MeasureUnit.DAY)
            minutes > 0 && minutes % 60 == 0 -> Measure(minutes / 60, MeasureUnit.HOUR)
            else -> Measure(minutes, MeasureUnit.MINUTE)
        }
        return format.format(measure)
    }

    /** A time in the phone's time zone and locale. */
    fun time(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)

    /** A date with its weekday in the phone's time zone and locale. */
    fun date(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withZone(ZoneId.systemDefault()).format(instant)

    fun date(day: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(day)

    fun shortDate(day: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)

    /** A wall time as the owner picks it, "09:30". */
    fun clock(time: LocalTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time)

    /** The UTC instant string the API takes (`...Z`). */
    fun utc(instant: Instant): String = instant.toString()

    @androidx.annotation.StringRes
    fun status(status: BipeBookingStatus): Int = when (status) {
        BipeBookingStatus.HELD -> R.string.bipe_agenda_status_held
        BipeBookingStatus.REQUESTED -> R.string.bipe_agenda_status_requested
        BipeBookingStatus.CONFIRMED -> R.string.bipe_agenda_status_confirmed
        BipeBookingStatus.DONE -> R.string.bipe_agenda_status_done
        BipeBookingStatus.CANCELLED -> R.string.bipe_agenda_status_cancelled
        BipeBookingStatus.NO_SHOW -> R.string.bipe_agenda_status_no_show
    }

    /**
     * The text of a failed Agenda call: each contract error id has its string (spec agenda-book error), a network
     * failure says so, anything else is [fallback].
     */
    fun error(context: Context, error: Throwable, @androidx.annotation.StringRes fallback: Int = R.string.bipe_agenda_error_save): String =
        when (error) {
            is BipeApiException.Server -> when (error.code) {
                BipeApiErrorCode.SLOT_TAKEN, BipeApiErrorCode.HOLD_EXPIRED, BipeApiErrorCode.TOO_MANY_OPEN_BOOKINGS,
                BipeApiErrorCode.OUTSIDE_NOTICE, BipeApiErrorCode.OUTSIDE_HORIZON, BipeApiErrorCode.AGENDA_NOT_PUBLISHED,
                BipeApiErrorCode.CANCELLATION_WINDOW_CLOSED, BipeApiErrorCode.RATE_LIMITED,
                BipeApiErrorCode.QUEUE_CLOSED, BipeApiErrorCode.ALREADY_IN_QUEUE, BipeApiErrorCode.PLACE_NOT_ACTIVE,
                BipeApiErrorCode.QUOTE_EXPIRED, BipeApiErrorCode.QUOTE_VERSION_CHANGED,
                BipeApiErrorCode.ORDER_ALREADY_OPEN, BipeApiErrorCode.ITEM_UNAVAILABLE, BipeApiErrorCode.PRICE_NOT_FIXED,
                BipeApiErrorCode.ORDERS_CLOSED, BipeApiErrorCode.PAYMENT_REQUIRES_VERIFIED_BUSINESS,
                BipeApiErrorCode.PIX_CHARGE_UNAVAILABLE -> error.localizedMessage(context)
                else -> context.getString(fallback)
            }
            is BipeApiException.Network -> error.localizedMessage(context)
            else -> context.getString(fallback)
        }

    @androidx.annotation.StringRes
    fun queueStatus(status: BipeQueuePlaceStatus): Int = when (status) {
        BipeQueuePlaceStatus.WAITING -> R.string.bipe_queue_status_waiting
        BipeQueuePlaceStatus.CALLED -> R.string.bipe_queue_called
        BipeQueuePlaceStatus.SERVED -> R.string.bipe_queue_status_served
        BipeQueuePlaceStatus.NO_SHOW -> R.string.bipe_queue_status_no_show
        BipeQueuePlaceStatus.LEFT -> R.string.bipe_queue_status_left
    }

    @androidx.annotation.StringRes
    fun quoteStatus(status: BipeQuoteStatus): Int = when (status) {
        BipeQuoteStatus.OFFERED -> R.string.bipe_quote_status_offered
        BipeQuoteStatus.ACCEPTED -> R.string.bipe_quote_status_accepted
        BipeQuoteStatus.REFUSED -> R.string.bipe_quote_status_refused
        BipeQuoteStatus.EXPIRED -> R.string.bipe_quote_status_expired
    }

    @androidx.annotation.StringRes
    fun orderStatus(status: BipeOrderStatus): Int = when (status) {
        BipeOrderStatus.SENT -> R.string.bipe_order_status_sent
        BipeOrderStatus.ACCEPTED -> R.string.bipe_order_status_accepted
        BipeOrderStatus.REFUSED -> R.string.bipe_order_status_refused
        BipeOrderStatus.READY -> R.string.bipe_order_status_ready
        BipeOrderStatus.DONE -> R.string.bipe_order_status_done
        BipeOrderStatus.CANCELLED -> R.string.bipe_order_status_cancelled
    }

    @androidx.annotation.StringRes
    fun refuseReason(reason: BipeOrderRefuseReason): Int = when (reason) {
        BipeOrderRefuseReason.UNAVAILABLE -> R.string.bipe_order_refuse_unavailable
        BipeOrderRefuseReason.CLOSED -> R.string.bipe_order_refuse_closed
        BipeOrderRefuseReason.CANNOT_DELIVER -> R.string.bipe_order_refuse_cannot_deliver
        BipeOrderRefuseReason.CANNOT_FULFIL -> R.string.bipe_order_refuse_cannot_fulfil
    }

    @androidx.annotation.StringRes
    fun fulfilment(id: String): Int = if (id == FULFILMENT_DELIVERY) R.string.bipe_order_delivery else R.string.bipe_order_pickup

    /** A quantity as the line shows it: "2", or "1,5" for a part (quote lines take decimals). */
    fun quantity(value: Double): String {
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e12) return value.toLong().toString()
        return DecimalFormat("0.###", DecimalFormatSymbols(Locale.getDefault())).format(value)
    }

    /** One line, bipe_order_line ("2 × Pão") with its total in reais. */
    fun line(context: Context, line: BipeCommerceLine): String =
        context.getString(R.string.bipe_order_line, quantity(line.quantity), line.name) + " · " + reais(line.totalAmountCents)

    /** A quantity the owner typed ("2", "1,5"); `null` when it is not a positive number. */
    fun quantityInput(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 100_000 && !it.isNaN() }

    /** Spec quote-builder validUntil: the end of the local [day] in the phone's time zone, as a UTC instant with a trailing Z. */
    fun endOfDay(day: LocalDate): String =
        ZonedDateTime.of(day, LocalTime.of(23, 59, 59), ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS).toString()
}

/** The calendar file of agenda-booking-card addToCalendar: a local .ics opened in the system calendar. Nothing goes to a server. */
object BookingCalendar {
    fun open(context: Context, id: String, start: Instant, end: Instant, title: String) {
        val dir = File(context.cacheDir, "agenda").apply { mkdirs() }
        val file = File(dir, "bipe-" + id.filter { it.isLetterOrDigit() }.take(64) + ".ics")
        file.writeText(ics(id, start, end, title), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/calendar").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            // No calendar app takes the file: the share sheet instead.
            val send = Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        }
    }

    /** One VEVENT in UTC (RFC 5545). */
    fun ics(id: String, start: Instant, end: Instant, title: String): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"))
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Bipe//Agenda//PT",
            "BEGIN:VEVENT",
            "UID:" + escape(id) + "@bipe.chat",
            "DTSTAMP:" + stamp.format(Instant.now()),
            "DTSTART:" + stamp.format(start),
            "DTEND:" + stamp.format(end),
            "SUMMARY:" + escape(title),
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return lines.joinToString(separator = "\r\n", postfix = "\r\n")
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")
}

/** Catálogo photos from their public bipe-api URL, kept in memory. */
object CatalogPhotos {
    private val cache = LruCache<String, ImageBitmap>(60)

    suspend fun load(url: String, maxSide: Int): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bytes = withContext(Dispatchers.IO) { runCatching { URL(url).readBytes() }.getOrNull() } ?: return null
        val image = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } ?: return null
        cache.put(url, image)
        return image
    }
}

/** A catalogue item's photo, or an empty tile while it loads or when there is none. */
@Composable
fun CatalogPhoto(url: String?, name: String, size: Dp) {
    val colors = BipeTheme.colors
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { image = url?.let { CatalogPhotos.load(it, (size.value * 3).toInt()) } }
    Box(Modifier.size(size).clip(RoundedCornerShape(12)).background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        image?.let {
            Image(it, contentDescription = stringResource(R.string.bipe_catalog_photo_a11y, name), contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** The full-screen frame of a business flow over whatever is on screen. Back closes it unless the flow handles back. */
@Composable
fun BusinessFullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) { content() }
        }
    }
}

/** Grows by one each time the app comes back to the front: business cards and states refresh then (spec business-chat-bar). */
@Composable
fun rememberForegroundRevision(): Int {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = remember(context) { context.lifecycleOwner()?.lifecycle }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision += 1 }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return revision
}

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.lifecycleOwner()
    else -> null
}

/** A field that opens a list to pick one value from (times, dates, people, services). */
@Composable
fun <T> PickerField(label: String, value: String, options: List<Pair<T, String>>, enabled: Boolean = true, onPick: (T) -> Unit) {
    val colors = BipeTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(value, color = colors.inkPrimary, modifier = Modifier.fillMaxWidth())
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(key)
                    })
                }
            }
        }
    }
}

/** Wall times of a day every [stepMinutes], for the owner's pickers. */
fun dayTimes(stepMinutes: Int): List<LocalTime> {
    val step = stepMinutes.coerceIn(5, 240)
    return (0 until 24 * 60 step step).map { LocalTime.of(it / 60, it % 60) }
}
