// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.common.BipeTonalButton
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.media.MediaFormat
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeLightColors
import chat.bipe.shared.generated.BipeScreen
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/** Spec year-recap (ADR 0223): the numbers of one calendar year, counted on this phone only. */
data class YearRecap(
    val year: Int,
    val messagesSent: Int = 0,
    val activeDays: Int = 0,
    val people: Int = 0,
    val topChatName: String? = null,
    val topReaction: String? = null,
    val longestVoiceMillis: Long? = null,
    /** Own messages a month (January first), a local hour (0 first) and a weekday (Sunday first). */
    val byMonth: List<Int> = List(12) { 0 },
    val byHour: List<Int> = List(24) { 0 },
    val byWeekday: List<Int> = List(7) { 0 },
    val isPartial: Boolean = false,
) {
    /** Spec year-recap messagesByHour: the busiest block of six hours. */
    val peakPeriod: Int
        get() {
            val blocks = (0 until 24 step 6).map { start -> byHour.subList(start, start + 6).sum() }
            return when (blocks.indexOf(blocks.max())) {
                0 -> R.string.bipe_year_recap_peak_dawn
                1 -> R.string.bipe_year_recap_peak_morning
                2 -> R.string.bipe_year_recap_peak_afternoon
                else -> R.string.bipe_year_recap_peak_evening
            }
        }

    companion object {
        /** Spec year-recap notes: the current year; in January, the year before. */
        fun year(today: LocalDate = LocalDate.now()): Int = if (today.monthValue == 1) today.year - 1 else today.year
    }
}

/** Counts own events of one year, room by room. Only own actions count (ADR 0223). */
class YearRecapTally(private val ownUserId: String, year: Int, private val zone: ZoneId = ZoneId.systemDefault()) {
    val start: Long = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val end: Long = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private var messages = 0
    private val days = mutableSetOf<LocalDate>()
    private var dms = 0
    private val perRoom = mutableMapOf<String, Int>()
    private val reactions = mutableMapOf<String, Pair<Int, Long>>()
    private var longestVoice: Long? = null
    private val byMonth = IntArray(12)
    private val byHour = IntArray(24)
    private val byWeekday = IntArray(7)
    var isPartial = false
        private set

    private fun inYear(timestamp: Long) = timestamp in start until end

    fun markPartial() {
        isPartial = true
    }

    fun add(roomId: String, isDm: Boolean, events: List<EventTimelineItem>) {
        var sent = 0
        for (event in events) {
            val content = (event.content as? TimelineItemContent.MsgLike)?.content ?: continue
            // Own reactions count by their own time, also on an older message.
            for (reaction in content.reactions) {
                for (sender in reaction.senders) {
                    val at = sender.timestamp.toLong()
                    if (sender.senderId != ownUserId || !inYear(at)) continue
                    val old = reactions[reaction.key]
                    reactions[reaction.key] = ((old?.first ?: 0) + 1) to maxOf(old?.second ?: 0L, at)
                }
            }
            val at = event.timestamp.toLong()
            if (!inYear(at)) continue
            if (content.kind is MsgLikeKind.UnableToDecrypt) isPartial = true
            if (!event.isOwn || content.kind == MsgLikeKind.Redacted) continue
            sent += 1
            val time = Instant.ofEpochMilli(at).atZone(zone)
            days += time.toLocalDate()
            byMonth[time.monthValue - 1] += 1
            byHour[time.hour] += 1
            byWeekday[time.dayOfWeek.value % 7] += 1
            val audio = ((content.kind as? MsgLikeKind.Message)?.content?.msgType as? MessageType.Audio)?.content
            if (audio?.voice != null) {
                (audio.audio?.duration ?: audio.info?.duration)?.toMillis()?.let { longestVoice = maxOf(longestVoice ?: 0L, it) }
            }
        }
        if (sent == 0) return
        messages += sent
        perRoom[roomId] = (perRoom[roomId] ?: 0) + sent
        if (isDm) dms += 1
    }

    /** The recap; chatName names the busiest chat with the chat-list row name. */
    fun recap(year: Int, chatName: (String) -> String?): YearRecap {
        val topRoom = perRoom.maxByOrNull { it.value }?.key
        val topReaction = reactions.entries.maxWithOrNull(compareBy({ it.value.first }, { it.value.second }))?.key
        return YearRecap(year, messages, days.size, dms, topRoom?.let(chatName), topReaction, longestVoice,
            byMonth.toList(), byHour.toList(), byWeekday.toList(), isPartial)
    }
}

/** Spec year-recap: reads every joined chat once while the screen is open. Keeps nothing on disk. */
class YearRecapModel(private val session: AccountSession, private val chatList: ChatListModel, private val scope: CoroutineScope) {
    enum class Phase { READING, READY, EMPTY }

    data class State(val phase: Phase = Phase.READING, val roomsDone: Int = 0, val roomsTotal: Int = 0, val recap: YearRecap? = null)

    val year = YearRecap.year()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** Action appear. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            val rooms = runCatching { session.client.rooms() }.getOrDefault(emptyList()).filter { it.membership() == Membership.JOINED }
            _state.value = _state.value.copy(roomsTotal = rooms.size)
            val tally = YearRecapTally(session.userId, year)
            for (room in rooms) {
                if (!isActive) return@launch
                read(room, tally)
                _state.value = _state.value.copy(roomsDone = _state.value.roomsDone + 1)
            }
            val names = chatList.chats.value.associate { it.roomId to it.name }
            val recap = tally.recap(year) { names[it] }
            _state.value = _state.value.copy(phase = if (recap.messagesSent == 0) Phase.EMPTY else Phase.READY, recap = recap)
        }
    }

    /** Action close: stops the reading and keeps nothing. */
    fun close() {
        job?.cancel()
    }

    private suspend fun read(room: Room, tally: YearRecapTally) {
        try {
            val isDm = runCatching { room.roomInfo().isDm }.getOrDefault(false)
            room.timeline().use { timeline ->
                val items = mutableListOf<TimelineItem>()
                val handle = timeline.addListener(object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        synchronized(items) { diff.forEach { items.applyDiff(it.toVectorDiff()) } }
                    }
                })
                try {
                    var reachedStart = false
                    var pages = 0
                    while (pages < MAX_PAGES) {
                        pages += 1
                        val hitStart = runCatching { timeline.paginateBackwards(PAGE) }.getOrNull()
                        if (hitStart == null) {
                            tally.markPartial()
                            reachedStart = true
                            break
                        }
                        // Diffs arrive on the listener after the page; give them a moment before the oldest time is read.
                        delay(100)
                        val oldest = synchronized(items) { items.firstNotNullOfOrNull { it.asEvent()?.timestamp?.toLong() } }
                        if (hitStart || (oldest != null && oldest < tally.start)) {
                            reachedStart = true
                            break
                        }
                    }
                    if (!reachedStart) tally.markPartial()
                    delay(200)
                    val events = synchronized(items) { items.mapNotNull { it.asEvent() } }
                    tally.add(room.id(), isDm, events)
                } finally {
                    handle.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            tally.markPartial()
        }
    }

    private companion object {
        /** Spec year-recap appear: 50 events a page, at most 40 pages a chat. */
        const val PAGE: UShort = 50u
        const val MAX_PAGES = 40
    }
}

/** Spec year-recap. */
@Composable
fun YearRecapScreen(session: AccountSession, chatList: ChatListModel, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { YearRecapModel(session, chatList, scope) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.YEAR_RECAP.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_year_recap_title), onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val recap = state.recap
                when {
                    state.phase == YearRecapModel.Phase.READING -> {
                        LinearProgressIndicator(
                            progress = { if (state.roomsTotal == 0) 0f else state.roomsDone.toFloat() / state.roomsTotal },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accentFill,
                        )
                        val format = NumberFormat.getIntegerInstance()
                        Text(stringResource(R.string.bipe_year_recap_reading, format.format(state.roomsDone), format.format(state.roomsTotal)),
                            style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    state.phase == YearRecapModel.Phase.EMPTY || recap == null ->
                        Text(stringResource(R.string.bipe_year_recap_empty, model.year.toString()), color = colors.inkSecondary)
                    else -> {
                        YearRecapCard(recap)
                        if (recap.isPartial) {
                            Text(stringResource(R.string.bipe_year_recap_partial), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                        BipeTonalButton(onClick = { YearRecapShare.share(context, recap) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(PhosphorIcon.SHARE_NETWORK.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.bipe_app_share))
                        }
                        Text(stringResource(R.string.bipe_year_recap_share_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
                Text(stringResource(R.string.bipe_year_recap_on_device), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
    }
}

/** Spec year-recap notes: a tile with no value does not show; 0 shows. */
private fun tiles(context: Context, recap: YearRecap): List<Pair<String, String>> {
    val format = NumberFormat.getIntegerInstance()
    return buildList {
        add(format.format(recap.messagesSent) to context.getString(R.string.bipe_year_recap_messages))
        add(format.format(recap.activeDays) to context.getString(R.string.bipe_year_recap_days))
        add(format.format(recap.people) to context.getString(R.string.bipe_year_recap_people))
        recap.topReaction?.let { add(it to context.getString(R.string.bipe_year_recap_top_reaction)) }
        recap.longestVoiceMillis?.let { add(MediaFormat.duration(it) to context.getString(R.string.bipe_year_recap_longest_voice)) }
    }
}

/** One bar chart of the card: values, axis labels (every labelEvery bars) and the name of the peak. */
private data class YearRecapChart(val title: String, val values: List<Int>, val labels: List<String>, val labelEvery: Int, val peak: String)

/** Spec year-recap charts: by month, by hour, by weekday from the locale's first weekday. Ties go to the earliest. */
private fun charts(context: Context, recap: YearRecap): List<YearRecapChart> {
    val locale = context.resources.configuration.locales[0]
    fun peakIndex(values: List<Int>) = values.indexOf(values.max())
    val month = YearRecapChart(context.getString(R.string.bipe_year_recap_by_month), recap.byMonth,
        Month.values().map { it.getDisplayName(TextStyle.NARROW, locale) }, 1,
        Month.of(peakIndex(recap.byMonth) + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale))
    val hourPattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    val hour = YearRecapChart(context.getString(R.string.bipe_year_recap_by_hour), recap.byHour, (0 until 24).map { it.toString() }, 6,
        DateTimeFormatter.ofPattern(hourPattern, locale).format(LocalTime.of(peakIndex(recap.byHour), 0)))
    // Sunday-first index of each weekday, from the locale's first weekday.
    val first = WeekFields.of(locale).firstDayOfWeek.value % 7
    val order = (0 until 7).map { (first + it) % 7 }
    fun day(index: Int) = DayOfWeek.of(if (index == 0) 7 else index)
    val weekdayValues = order.map { recap.byWeekday[it] }
    val weekday = YearRecapChart(context.getString(R.string.bipe_year_recap_by_weekday), weekdayValues,
        order.map { day(it).getDisplayName(TextStyle.NARROW, locale) }, 1,
        day(order[peakIndex(weekdayValues)]).getDisplayName(TextStyle.FULL, locale))
    return listOf(month, hour, weekday)
}

/** Plain bars, no chart library. */
@Composable
private fun Bars(chart: YearRecapChart) {
    val colors = BipeTheme.colors
    val top = chart.values.max()
    val peak = chart.values.indexOf(top)
    val spacing = if (chart.values.size > 12) 2.dp else 4.dp
    val peakText = stringResource(R.string.bipe_year_recap_chart_peak, chart.peak)
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${chart.title}. $peakText" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(chart.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        Row(Modifier.fillMaxWidth().height(BAR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Bottom) {
            chart.values.forEachIndexed { index, value ->
                val share = if (top == 0) 0f else value.toFloat() / top
                val fill = if (index == peak) colors.accentFill else colors.accentFill.copy(alpha = 0.3f)
                Box(Modifier.weight(1f).height(maxOf(2.dp, BAR_HEIGHT * share)).background(fill, RoundedCornerShape(2.dp)))
            }
        }
        // One label for each group of labelEvery bars; the groups have the width of their bars.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            for (index in chart.labels.indices step chart.labelEvery) {
                Text(chart.labels[index], Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary,
                    maxLines = 1, textAlign = if (chart.labelEvery == 1) TextAlign.Center else TextAlign.Start)
            }
        }
        Text(peakText, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

private val BAR_HEIGHT = 64.dp

/** The numbers on the screen, with the busiest chat; the shared card leaves the chat out (ADR 0223). */
@Composable
private fun YearRecapCard(recap: YearRecap) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceRaised, MaterialTheme.shapes.large).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(PhosphorIcon.CHART_BAR.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.bipe_year_recap_card_title, recap.year.toString()), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = colors.accentInk)
        }
        tiles(context, recap).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (value, label) -> Tile(value, label, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        recap.topChatName?.let { Tile(it, stringResource(R.string.bipe_year_recap_top_chat), Modifier.fillMaxWidth()) }
        Text(stringResource(recap.peakPeriod), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        charts(context, recap).forEach { Bars(it) }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    val colors = BipeTheme.colors
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary, maxLines = 2)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

/** Action share: the card image on the light theme, numbers only. Nothing leaves the phone before the person picks a target. */
object YearRecapShare {
    private const val DIR = "year-recap"
    private const val CARD_WIDTH = 1080
    private const val PADDING = 60
    private const val GAP = 48
    private const val CHART_GAP = 12
    private const val BARS = 160f

    fun share(context: Context, recap: YearRecap): Boolean {
        val file = runCatching { card(context, recap) }.getOrNull() ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return runCatching { context.startActivity(Intent.createChooser(send, null)) }.isSuccess
    }

    private fun card(context: Context, recap: YearRecap): File {
        val colors = BipeLightColors
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 44f
            color = colors.accentInk.toArgb()
        }
        val valuePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 72f
            color = colors.inkPrimary.toArgb()
        }
        val labelPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = colors.inkSecondary.toArgb()
        }
        val periodPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 44f
            color = colors.inkPrimary.toArgb()
        }
        val chartTitlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 38f
            color = colors.inkPrimary.toArgb()
        }
        val axisPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            color = colors.inkSecondary.toArgb()
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun lineHeight(paint: Paint) = paint.descent() - paint.ascent()
        val inner = CARD_WIDTH - 2 * PADDING
        val period = context.getString(recap.peakPeriod)
        val periodLayout = StaticLayout.Builder.obtain(period, 0, period.length, periodPaint, inner).build()
        val cardCharts = charts(context, recap)
        val chartHeight = lineHeight(chartTitlePaint) + CHART_GAP + BARS + CHART_GAP + lineHeight(axisPaint) + CHART_GAP + lineHeight(labelPaint)
        val iconSize = 52
        val width = (CARD_WIDTH - 2 * PADDING - GAP) / 2
        fun layout(text: String, paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .build()
        val rows = tiles(context, recap).chunked(2).map { pair -> pair.map { (value, label) -> layout(value, valuePaint) to layout(label, labelPaint) } }
        val rowHeights = rows.map { row -> row.maxOf { (value, label) -> value.height + 8 + label.height } }
        val height = PADDING + iconSize + GAP + rowHeights.sum() + GAP * (rows.size - 1).coerceAtLeast(0) +
            GAP + periodLayout.height + cardCharts.size * (GAP + ceil(chartHeight).toInt()) + PADDING
        val image = Bitmap.createBitmap(CARD_WIDTH, height, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(colors.surfaceRaised.toArgb())
            ContextCompat.getDrawable(context, PhosphorIcon.CHART_BAR.drawable)?.mutate()?.let { icon ->
                icon.setTint(colors.accentInk.toArgb())
                icon.setBounds(PADDING, PADDING, PADDING + iconSize, PADDING + iconSize)
                icon.draw(this)
            }
            val baseline = PADDING + iconSize / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
            drawText(context.getString(R.string.bipe_year_recap_card_title, recap.year.toString()), PADDING + iconSize + 20f, baseline, titlePaint)
            var top = (PADDING + iconSize + GAP).toFloat()
            rows.forEachIndexed { index, row ->
                row.forEachIndexed { column, (value, label) ->
                    save()
                    translate((PADDING + column * (width + GAP)).toFloat(), top)
                    value.draw(this)
                    translate(0f, value.height + 8f)
                    label.draw(this)
                    restore()
                }
                top += rowHeights[index] + GAP
            }
            save()
            translate(PADDING.toFloat(), top)
            periodLayout.draw(this)
            restore()
            top += periodLayout.height
            cardCharts.forEach { chart ->
                top += GAP
                drawText(chart.title, PADDING.toFloat(), top - chartTitlePaint.ascent(), chartTitlePaint)
                top += lineHeight(chartTitlePaint) + CHART_GAP
                val most = chart.values.max()
                val peak = chart.values.indexOf(most)
                val spacing = if (chart.values.size > 12) 4f else 8f
                val barWidth = (inner - spacing * (chart.values.size - 1)) / chart.values.size
                fun left(index: Int) = PADDING + index * (barWidth + spacing)
                chart.values.forEachIndexed { index, value ->
                    val bar = maxOf(4f, if (most == 0) 0f else BARS * value / most)
                    barPaint.color = (if (index == peak) colors.accentFill else colors.accentFill.copy(alpha = 0.3f)).toArgb()
                    drawRoundRect(left(index), top + BARS - bar, left(index) + barWidth, top + BARS, 4f, 4f, barPaint)
                }
                top += BARS + CHART_GAP
                axisPaint.textAlign = if (chart.labelEvery == 1) Paint.Align.CENTER else Paint.Align.LEFT
                for (index in chart.labels.indices step chart.labelEvery) {
                    val x = if (chart.labelEvery == 1) left(index) + barWidth / 2 else left(index)
                    drawText(chart.labels[index], x, top - axisPaint.ascent(), axisPaint)
                }
                top += lineHeight(axisPaint) + CHART_GAP
                drawText(context.getString(R.string.bipe_year_recap_chart_peak, chart.peak), PADDING.toFloat(), top - labelPaint.ascent(), labelPaint)
                top += lineHeight(labelPaint)
            }
        }
        val dir = File(context.cacheDir, DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        return File(dir, "bipe-meu-ano.png").also { out ->
            FileOutputStream(out).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
