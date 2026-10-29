// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.RoomMessageEventMessageType
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** Spec chat-media-gallery (bipe-chat/bipe#672): the photos, videos and files of one chat, newest first. */
class ChatMediaGalleryModel(private val session: AccountSession, private val roomId: String, private val scope: CoroutineScope) {
    enum class Tab { MEDIA, FILES }

    /** Type GalleryItem. */
    data class Item(val rowId: String, val media: MediaMessage)

    data class State(
        val items: List<Item> = emptyList(),
        val isLoading: Boolean = true,
        val reachedStart: Boolean = false,
    ) {
        val mediaItems: List<Item> get() = items.filter { it.media.kind != MediaMessage.Kind.FILE }
        val fileItems: List<Item> get() = items.filter { it.media.kind == MediaMessage.Kind.FILE }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null
    private val timelineItems = mutableListOf<TimelineItem>()
    private var paging = false

    /** Action appear. */
    fun start() {
        if (timeline != null) return
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull()
            val configuration = TimelineConfiguration(
                focus = TimelineFocus.Live(hideThreadedEvents = false),
                filter = TimelineFilter.OnlyMessage(listOf(RoomMessageEventMessageType.IMAGE, RoomMessageEventMessageType.VIDEO, RoomMessageEventMessageType.FILE)),
                internalIdPrefix = "gallery",
                dateDividerMode = DateDividerMode.DAILY,
                trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                reportUtds = false,
            )
            val timeline = room?.let { runCatching { it.timelineWithConfiguration(configuration) }.getOrNull() }
            if (timeline == null) {
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            this@ChatMediaGalleryModel.timeline = timeline
            val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
            handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    diffs.trySend(diff)
                }
            })
            launch {
                while (!_state.value.reachedStart && maxOf(_state.value.mediaItems.size, _state.value.fileItems.size) < FIRST_FILL) {
                    if (!loadMore()) break
                }
                _state.value = _state.value.copy(isLoading = false)
            }
            for (batch in diffs) {
                batch.forEach { timelineItems.applyDiff(it.toVectorDiff()) }
                rebuild()
            }
        }
    }

    /** Action loadMore. false when the page failed. */
    suspend fun loadMore(): Boolean {
        val timeline = timeline ?: return true
        if (_state.value.reachedStart || paging) return true
        paging = true
        val hitStart = runCatching { timeline.paginateBackwards(PAGE) }.getOrNull()
        paging = false
        if (hitStart == null) return false
        _state.value = _state.value.copy(reachedStart = hitStart)
        return true
    }

    fun loadMoreLater() {
        scope.launch { loadMore() }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    private fun rebuild() {
        val items = timelineItems.asReversed().mapNotNull { item ->
            val content = item.asEvent()?.content as? TimelineItemContent.MsgLike ?: return@mapNotNull null
            val media = (MediaContent.of(content.content.kind) as? MediaContent.Media)?.media ?: return@mapNotNull null
            if (media.kind == MediaMessage.Kind.STICKER) null else Item(item.uniqueId().id, media)
        }
        _state.value = _state.value.copy(items = items)
    }

    private companion object {
        /** Action appear loads pages until a tab has this many items. */
        const val FIRST_FILL = 30
        const val PAGE: UShort = 50u
    }
}

/** Spec chat-media-gallery, full screen over group-details or person-profile. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMediaGalleryScreen(session: AccountSession, media: MediaLoader, roomId: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { ChatMediaGalleryModel(session, roomId, scope) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    var tab by remember { mutableStateOf(ChatMediaGalleryModel.Tab.MEDIA) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var openingFile by remember { mutableStateOf(false) }
    var fileFailed by remember { mutableStateOf(false) }

    /** Action open for a file: downloads it to the cache and opens it, as timeline-media does. */
    fun openFile(file: MediaMessage) {
        if (openingFile) return
        openingFile = true
        fileFailed = false
        scope.launch {
            val local = runCatching { media.file(context, file.source, file.filename, file.mimeType) }.getOrNull()
            openingFile = false
            if (local == null) fileFailed = true else runCatching { MediaIntents.open(context, local, file.mimeType) }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (viewing != null) viewing = null else onClose() }
        Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_MEDIA_GALLERY.id), color = colors.surfaceBase) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close), color = colors.accentInk) }
                    Text(stringResource(R.string.bipe_gallery_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                }
                TabRow(selectedTabIndex = tab.ordinal, containerColor = colors.surfaceBase, contentColor = colors.accentInk) {
                    Tab(selected = tab == ChatMediaGalleryModel.Tab.MEDIA, onClick = { tab = ChatMediaGalleryModel.Tab.MEDIA }, text = { Text(stringResource(R.string.bipe_gallery_media)) })
                    Tab(selected = tab == ChatMediaGalleryModel.Tab.FILES, onClick = { tab = ChatMediaGalleryModel.Tab.FILES }, text = { Text(stringResource(R.string.bipe_gallery_files)) })
                }
                val shown = if (tab == ChatMediaGalleryModel.Tab.MEDIA) state.mediaItems else state.fileItems
                when {
                    shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (state.isLoading) {
                            CircularProgressIndicator(color = colors.accentFill)
                        } else {
                            Text(
                                stringResource(if (tab == ChatMediaGalleryModel.Tab.MEDIA) R.string.bipe_gallery_empty_media else R.string.bipe_gallery_empty_files),
                                color = colors.inkSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    tab == ChatMediaGalleryModel.Tab.MEDIA -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(shown, key = { _, item -> item.rowId }) { index, item ->
                            if (index == shown.lastIndex) LaunchedEffect(item.rowId) { model.loadMoreLater() }
                            GalleryTile(item.media, media) { viewing = item.rowId }
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(shown, key = { _, item -> item.rowId }) { index, item ->
                            if (index == shown.lastIndex) LaunchedEffect(item.rowId) { model.loadMoreLater() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !openingFile) { openFile(item.media) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(painterResource(PhosphorIcon.FILE.drawable), contentDescription = null, tint = colors.glyphPrimary, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.media.filename, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                    item.media.sizeLabel(context)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
                                }
                            }
                        }
                    }
                }
                if (fileFailed) {
                    Text(
                        stringResource(R.string.bipe_app_media_failed),
                        color = colors.trustDangerInk,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        val opened = viewing
        if (opened != null) {
            val pages = state.mediaItems
            val start = pages.indexOfFirst { it.rowId == opened }.coerceAtLeast(0)
            val pager = rememberPagerState(initialPage = start) { pages.size }
            Box(Modifier.fillMaxSize().background(colors.surfaceBase)) {
                HorizontalPager(state = pager, key = { pages[it].rowId }) { page ->
                    MediaPage(pages[page].media, media, onTap = {})
                }
                val closeLabel = stringResource(R.string.bipe_app_close)
                IconButton(
                    onClick = { viewing = null },
                    modifier = Modifier.padding(16.dp).mediaScrim(CircleShape).semantics { contentDescription = closeLabel },
                ) {
                    Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = null)
                }
            }
        }
    }
}

/** One square tile of the grid; a video shows its duration. */
@Composable
private fun GalleryTile(media: MediaMessage, loader: MediaLoader, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    var image by remember(media.source) { mutableStateOf<LoadedImage?>(null) }
    LaunchedEffect(media.source) { image = loader.previewImage(media, 300) }
    val label = stringResource(media.kindLabel)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(colors.surfaceSunken)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = label },
    ) {
        image?.let { Image(it.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (media.kind == MediaMessage.Kind.VIDEO) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).mediaScrim(RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(painterResource(PhosphorIcon.PLAY.drawable), contentDescription = null, modifier = Modifier.size(10.dp))
                media.durationLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
