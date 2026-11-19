// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import chat.bipe.app.location.BipeMap
import chat.bipe.app.location.GeoUri
import chat.bipe.app.location.MapOffset
import chat.bipe.app.location.MapPoint
import chat.bipe.desktop.chat.DesktopLocation
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.chat.LoadPhase
import chat.bipe.shared.generated.BipeRegistries
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mapClient by lazy {
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()
}

@Composable internal fun TimelineLocation(location: DesktopLocation, s: DesktopCatalog, colors: DesktopColors, open: () -> Unit) {
    val title = locationLabel(location, s)
    Column(Modifier.widthIn(min = 230.dp, max = 340.dp)) {
        val point = location.point
        if (point != null) {
            StaticMap(point, point, title, s, colors, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                open = open)
            TextButton(onClick = open) { Text(title) }
        } else Text(s.text("bipe_app_location_live_waiting"))
        location.description?.let { Text(it) }
        location.live?.let { Text(liveStatus(it, s), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable internal fun LocationDialog(sender: String, location: DesktopLocation, s: DesktopCatalog, colors: DesktopColors, close: () -> Unit) {
    val point = location.point ?: return
    var centre by remember { mutableStateOf(point) }
    var openFailed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val title = locationLabel(location, s)
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sender, style = MaterialTheme.typography.labelMedium)
            StaticMap(centre, point, title, s, colors, Modifier.fillMaxWidth().aspectRatio(4f / 3f), pan = { centre = it })
            MapZoom(centre, s) { centre = it }
            location.description?.let { Text(it) }
            location.live?.let { Text(liveStatus(it, s)) }
            if (openFailed) Text(s.text("bipe_app_location_unavailable"))
            Row {
                TextButton(onClick = {
                    val geo = GeoUri.make(point.latitude, point.longitude)
                    openFailed = runCatching { uriHandler.openUri(geo) }.isFailure
                }) { Text(s.text("bipe_app_location_open_maps")) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(GeoUri.make(point.latitude, point.longitude)))
                }) { Text(s.text("bipe_app_copy")) }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } })
}

private enum class PinPhase { PICKING, SENDING, FAILED }

@Composable internal fun SendLocationDialog(model: DesktopTimeline, reply: String?, s: DesktopCatalog, colors: DesktopColors, close: () -> Unit) {
    var centre by remember(model) { mutableStateOf(MapPoint.BRAZIL) }
    var phase by remember(model) { mutableStateOf(PinPhase.PICKING) }
    val scope = rememberCoroutineScope()
    val title = s.text("bipe_app_location_send_title")
    AlertDialog(onDismissRequest = { if (phase != PinPhase.SENDING) close() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.text("bipe_app_location_drag_hint"))
            StaticMap(centre, centre, title, s, colors, Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                pan = if (phase == PinPhase.SENDING) null else { point -> centre = point })
            MapZoom(centre, s, enabled = phase != PinPhase.SENDING) { centre = it }
            Text(GeoUri.format(centre.latitude) + ", " + GeoUri.format(centre.longitude), style = MaterialTheme.typography.labelMedium)
            if (phase == PinPhase.FAILED) Text(s.text("bipe_app_location_send_failed"))
        }
    }, dismissButton = { TextButton(onClick = close, enabled = phase != PinPhase.SENDING) { Text(s.text("bipe_app_cancel")) } },
        confirmButton = {
            TextButton(enabled = phase != PinPhase.SENDING, onClick = {
                if (phase == PinPhase.SENDING) return@TextButton
                val selected = centre
                phase = PinPhase.SENDING
                scope.launch {
                    try {
                        model.sendPin(selected, s.text("bipe_app_location_body", GeoUri.make(selected.latitude, selected.longitude)), reply)
                        close()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { phase = PinPhase.FAILED }
                }
            }) { Text(s.text("bipe_app_location_send_pin")) }
        })
}

@Composable private fun MapZoom(centre: MapPoint, s: DesktopCatalog, enabled: Boolean = true, update: (MapPoint) -> Unit) {
    Row {
        TextButton(onClick = { update(centre.withZoom(centre.zoom - 1)) },
            enabled = enabled && centre.zoom > BipeRegistries.MAPS_MIN_ZOOM) { Text(s.text("bipe_app_location_zoom_out")) }
        TextButton(onClick = { update(centre.withZoom(centre.zoom + 1)) },
            enabled = enabled && centre.zoom < centre.maxZoom) { Text(s.text("bipe_app_location_zoom_in")) }
    }
}

@Composable private fun StaticMap(centre: MapPoint, pin: MapPoint, title: String, s: DesktopCatalog, colors: DesktopColors,
                                  modifier: Modifier, open: (() -> Unit)? = null, pan: ((MapPoint) -> Unit)? = null) {
    val density = LocalDensity.current.density
    var retry by remember(centre) { mutableStateOf(0) }
    var mapModifier = modifier.background(colors["surface.raised"])
    if (open != null) mapModifier = mapModifier.clickable(role = Role.Button, onClickLabel = title, onClick = open)
    if (pan != null) mapModifier = mapModifier.focusable().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val offset = when (event.key) {
            Key.DirectionLeft -> MapOffset(-72.0, 0.0)
            Key.DirectionRight -> MapOffset(72.0, 0.0)
            Key.DirectionUp -> MapOffset(0.0, -72.0)
            Key.DirectionDown -> MapOffset(0.0, 72.0)
            else -> return@onKeyEvent false
        }
        pan(BipeMap.place(offset, centre))
        true
    }.pointerInput(centre, density) {
        var movement = Offset.Zero
        detectDragGestures(onDragStart = { movement = Offset.Zero }, onDragEnd = {
            pan(BipeMap.place(MapOffset(-movement.x.toDouble() / density, -movement.y.toDouble() / density), centre))
        }) { change, delta ->
            change.consume()
            movement += delta
        }
    }.semantics {
        customActions = listOf(
            CustomAccessibilityAction(s.text("bipe_app_location_zoom_in")) { pan(centre.withZoom(centre.zoom + 1)); true },
            CustomAccessibilityAction(s.text("bipe_app_location_zoom_out")) { pan(centre.withZoom(centre.zoom - 1)); true },
        )
    }
    BoxWithConstraints(mapModifier, contentAlignment = Alignment.Center) {
        val request = BipeMap.request(centre, maxWidth.value, maxHeight.value, colors.dark)
        val state by key(request, retry) { produceState<LoadPhase<ImageBitmap>>(LoadPhase.Loading) {
            if (request == null) return@produceState
            value = try { LoadPhase.Ready(loadMap(request.url)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LoadPhase.Failed }
        } }
        when (val frame = state) {
            LoadPhase.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
            LoadPhase.Failed -> TextButton(onClick = { retry++ }) { Text(s.text("bipe_app_media_failed")) }
            is LoadPhase.Ready -> {
                Image(frame.value, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                val point = BipeMap.offset(pin, centre)
                Canvas(Modifier.fillMaxSize()) {
                    val middle = Offset(size.width / 2 + (point.x * density).toFloat(),
                        size.height / 2 + (point.y * density).toFloat())
                    if (middle.x in 0f..size.width && middle.y in 0f..size.height) {
                        drawCircle(colors["surface.base"], radius = 9.dp.toPx(), center = middle)
                        drawCircle(colors["accent.fill"], radius = 6.dp.toPx(), center = middle)
                    }
                }
            }
        }
    }
}

private suspend fun loadMap(url: String): ImageBitmap = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
    val response = mapClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
    val bytes = response.body().use { input ->
        check(response.statusCode() == 200)
        input.readNBytes(8 * 1024 * 1024 + 1)
    }
    check(bytes.size in 1..(8 * 1024 * 1024))
    checkNotNull(decodePicture(bytes, 2048))
}

private fun locationLabel(location: DesktopLocation, s: DesktopCatalog): String = s.text(when {
    location.live != null -> "bipe_app_location_live_title"
    location.pinned -> "bipe_app_location_pinned"
    else -> "bipe_app_location_current"
})

@Composable private fun liveStatus(live: DesktopLocation.Live, s: DesktopCatalog): String {
    val now by produceState(System.currentTimeMillis(), live) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    if (!live.active || now >= live.endsAt) return s.text("bipe_app_location_live_ended")
    val until = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag(s.locale))
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(live.endsAt))
    return s.text("bipe_app_location_live_until", until)
}
