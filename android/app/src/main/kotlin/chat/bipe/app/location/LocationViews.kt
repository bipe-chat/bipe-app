// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeButton
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.LocationContent

/** Type LocationMessage of spec location-view. */
data class LocationMessage(val point: MapPoint, val isPin: Boolean, val description: String?) {
    val labelRes: Int get() = if (isPin) R.string.bipe_app_location_pinned else R.string.bipe_app_location_current

    /** Action openInMaps: the system maps intent. The place leaves Bipe only here, when the person taps. */
    fun openInMaps(context: Context, label: String) {
        val lat = GeoUri.format(point.latitude)
        val lon = GeoUri.format(point.longitude)
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(description ?: label)})")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // No maps app on the phone: nothing to hand over to.
        }
    }

    companion object {
        /** A beacon of a live location share (MSC3489): the sender's own position at the default zoom. */
        fun of(geoUri: String, description: String?): LocationMessage? {
            val (latitude, longitude) = GeoUri.parse(geoUri) ?: return null
            return LocationMessage(MapPoint.of(latitude, longitude), isPin = false, description = description?.takeIf { it.isNotBlank() })
        }

        /** null when the geo URI cannot be read (the row is then unsupported). */
        fun of(content: LocationContent): LocationMessage? {
            val (latitude, longitude) = GeoUri.parse(content.geoUri) ?: return null
            return LocationMessage(
                point = MapPoint.of(latitude, longitude, content.zoomLevel?.toInt() ?: BipeRegistries.MAPS_DEFAULT_ZOOM),
                // MSC3488: a missing or unknown asset is the sender's own position.
                isPin = content.asset == AssetType.PIN,
                description = content.description?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Static map images of this process, by URL, so a timeline scroll does not fetch them again. */
private object StaticMapCache {
    private val images = LruCache<String, Bitmap>(24)

    suspend fun load(url: String): Bitmap? = images.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != 200) null else connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }?.also { images.put(url, it) }
}

/**
 * A static map image from maps.bipe.net.br (ADR 0131), centred on `centre`, at the size of the view.
 * While a new image loads, the last image stays, moved and scaled to where its area is now.
 * The server draws "© OpenStreetMap" into each image; the view never crops it.
 */
@Composable
fun StaticMap(centre: MapPoint, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val dark = isSystemInDarkTheme()
    BoxWithConstraints(modifier.clipToBounds().background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        val request = remember(centre, maxWidth, maxHeight, dark) { BipeMap.request(centre, maxWidth.value, maxHeight.value, dark) }
        var shown by remember { mutableStateOf<Pair<ImageBitmap, MapPoint>?>(null) }
        var failed by remember { mutableStateOf(false) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(request?.url, attempt) {
            val current = request ?: return@LaunchedEffect
            failed = false
            val bitmap = StaticMapCache.load(current.url)
            if (bitmap != null) shown = bitmap.asImageBitmap() to current.centre else failed = true
        }
        shown?.let { (image, at) ->
            val scale = 2f.pow(centre.zoom - at.zoom)
            val offset = BipeMap.offset(at, centre)
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x.toFloat().dp.toPx()
                    translationY = offset.y.toFloat().dp.toPx()
                },
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.bipe_app_media_failed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkPrimary,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceRaised)
                    .clickable { attempt += 1 }
                    .padding(10.dp),
            )
        } else if (shown?.second != centre) {
            CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(28.dp))
        }
    }
}

/** The pin on a map: a dot in the accent colour with a ring. */
@Composable
fun MapPin(modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    Box(
        modifier
            .size(20.dp)
            .shadow(2.dp, CircleShape)
            .background(colors.accentFill, CircleShape)
            .border(3.dp, colors.surfaceRaised, CircleShape),
    )
}

/**
 * A map the person can drag and zoom (specs location-send and location-view). `pinAt` null keeps the pin in the
 * middle (location-send); else the pin stays on that place while the map moves (location-view).
 */
@Composable
fun DraggableMap(
    centre: MapPoint,
    onCentreChange: (MapPoint) -> Unit,
    pinAt: MapPoint?,
    modifier: Modifier = Modifier,
    onMove: () -> Unit = {},
) {
    val colors = BipeTheme.colors
    val density = LocalDensity.current
    val current by rememberUpdatedState(centre)
    var drag by remember { mutableStateOf(Offset.Zero) }
    val title = stringResource(R.string.bipe_app_location_title)
    val zoomInLabel = stringResource(R.string.bipe_app_location_zoom_in)
    val zoomOutLabel = stringResource(R.string.bipe_app_location_zoom_out)
    fun zoom(step: Int) {
        val next = current.withZoom(current.zoom + step)
        if (next != current) {
            onCentreChange(next)
            onMove()
        }
    }
    Box(
        modifier
            .clipToBounds()
            .semantics {
                contentDescription = title
                customActions = listOf(
                    CustomAccessibilityAction(zoomInLabel) { zoom(1); true },
                    CustomAccessibilityAction(zoomOutLabel) { zoom(-1); true },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val moved = MapOffset(-drag.x / density.density.toDouble(), -drag.y / density.density.toDouble())
                        drag = Offset.Zero
                        onCentreChange(BipeMap.place(moved, current))
                        onMove()
                    },
                    onDragCancel = { drag = Offset.Zero },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StaticMap(centre, Modifier.fillMaxSize().offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) })
        val pinOffset = pinAt?.let { BipeMap.offset(it, centre) } ?: MapOffset(0.0, 0.0)
        MapPin(
            Modifier.offset {
                IntOffset(
                    (pinOffset.x.toFloat().dp.toPx() + drag.x).roundToInt(),
                    (pinOffset.y.toFloat().dp.toPx() + drag.y).roundToInt(),
                )
            },
        )
        // The attribution sits in the bottom right corner of the image; keep it visible.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 36.dp)
                .shadow(2.dp, RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised, RoundedCornerShape(10.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = { zoom(1) }, enabled = centre.zoom < BipeRegistries.MAPS_MAX_ZOOM) {
                Icon(painterResource(PhosphorIcon.PLUS.drawable), contentDescription = zoomInLabel, tint = colors.inkPrimary, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(Modifier.width(28.dp), color = colors.lineSubtle)
            IconButton(onClick = { zoom(-1) }, enabled = centre.zoom > BipeRegistries.MAPS_MIN_ZOOM) {
                Box(
                    Modifier
                        .semantics { contentDescription = zoomOutLabel }
                        .size(width = 16.dp, height = 2.dp)
                        .background(colors.inkPrimary, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/**
 * Spec location-view: the content of a location message row. The bubble, sender, reply quote, footer and
 * reactions come from the message row of spec timeline-rows.
 */
@Composable
fun TimelineLocationRow(location: LocationMessage, onOpenFull: () -> Unit) {
    val colors = BipeTheme.colors
    val label = stringResource(location.labelRes)
    Column(Modifier.width(260.dp).testTag(BipeScreen.LOCATION_VIEW.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = label, onClick = onOpenFull)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            StaticMap(location.point, Modifier.fillMaxSize())
            MapPin()
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.inkPrimary)
        location.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary) }
    }
}

/** Spec location-view: the full-screen map of one location. */
@Composable
fun LocationFullScreen(location: LocationMessage, senderName: String, sentAt: Long, live: LiveLocationMessage? = null, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    // A live row moves the pin with each beacon; the centre stays where the person put it (spec location-view notes).
    var centre by remember(if (live == null) location else Unit) { mutableStateOf(location.point) }
    val label = if (live == null) stringResource(location.labelRes) else stringResource(R.string.bipe_app_location_live_title)
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.LOCATION_VIEW.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(
                stringResource(if (live == null) R.string.bipe_app_location_title else R.string.bipe_app_location_live_title),
                onBack = onClose,
                backIcon = PhosphorIcon.X,
            )
            DraggableMap(centre, onCentreChange = { centre = it }, pinAt = location.point, modifier = Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                    location.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(senderName, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, maxLines = 1)
                        Text(
                            live?.statusLine(context) ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(sentAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
                BipeButton(onClick = { location.openInMaps(context, label) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_location_open_maps))
                }
            }
        }
    }
}
