// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.timeline.ChatTimelineModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.AssetType

/** State of the one position that location-send reads ("when in use" only, spec location-send action appear). */
private sealed interface PositionState {
    data object Idle : PositionState
    data object Locating : PositionState
    data class Found(val location: Location) : PositionState
    data object Denied : PositionState
    data object Unavailable : PositionState
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * One current position from LocationManager (no Google Play services). Calls back once with a location or null.
 * Returns a cancel function.
 */
@SuppressLint("MissingPermission")
private fun readOnePosition(context: Context, done: (Location?) -> Unit): () -> Unit {
    val manager = context.getSystemService(LocationManager::class.java) ?: run { done(null); return {} }
    val enabled = manager.getProviders(true)
    val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in enabled }
        ?: run { done(null); return {} }
    var finished = false
    fun finish(location: Location?) {
        if (finished) return
        finished = true
        done(location)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val signal = android.os.CancellationSignal()
        manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { finish(it) }
        return { signal.cancel() }
    }
    val listener = LocationListener { finish(it) }
    @Suppress("DEPRECATION")
    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    return { manager.removeUpdates(listener) }
}

/** Spec location-send: send the current location, or drag the map and send the place under the pin. */
@Composable
fun LocationSendScreen(timeline: ChatTimelineModel, session: AccountSession, replyToEventId: String?, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var position by remember { mutableStateOf<PositionState>(PositionState.Idle) }
    var centre by remember { mutableStateOf(MapPoint.BRAZIL) }
    var isAtOwnPosition by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) }
    var readRequest by remember { mutableStateOf(0) }
    var choosingDuration by remember { mutableStateOf(false) }
    var liveFailed by remember { mutableStateOf(false) }
    val liveShares by LiveLocationCenter.shares.collectAsState()
    BackHandler(enabled = !isSending, onBack = onClose)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) readRequest += 1 else position = PositionState.Denied
    }

    fun request() {
        if (hasLocationPermission(context)) {
            readRequest += 1
        } else {
            position = PositionState.Locating
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    LaunchedEffect(Unit) { request() }
    DisposableEffect(readRequest) {
        if (readRequest == 0) return@DisposableEffect onDispose {}
        position = PositionState.Locating
        val firstRead = readRequest == 1
        val cancel = readOnePosition(context) { location ->
            position = if (location != null) PositionState.Found(location) else PositionState.Unavailable
            // The map follows the first position, and the my-location button; after a drag it stays.
            if (location != null && (firstRead || !isAtOwnPosition)) {
                centre = MapPoint.of(location.latitude, location.longitude)
                isAtOwnPosition = true
            }
        }
        onDispose { cancel() }
    }

    val ownLocation = (position as? PositionState.Found)?.location
    val statusText = when (position) {
        PositionState.Locating -> stringResource(R.string.bipe_app_location_finding)
        PositionState.Unavailable -> stringResource(R.string.bipe_app_location_unavailable)
        else -> null
    }
    val myLocationLabel = stringResource(R.string.bipe_app_location_my_location)

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.LOCATION_SEND.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_app_location_send_title), onBack = onClose.takeUnless { isSending }, backIcon = PhosphorIcon.X)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DraggableMap(
                    centre = centre,
                    onCentreChange = { centre = it },
                    pinAt = null,
                    modifier = Modifier.fillMaxSize(),
                    onMove = { isAtOwnPosition = false },
                )
                statusText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkPrimary,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp, start = 64.dp, end = 64.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colors.surfaceRaised)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val own = ownLocation
                        if (own != null) {
                            centre = MapPoint.of(own.latitude, own.longitude)
                            isAtOwnPosition = true
                        } else {
                            request()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .shadow(2.dp, CircleShape)
                        .background(colors.surfaceRaised, CircleShape),
                ) {
                    Box(
                        Modifier
                            .semantics { contentDescription = myLocationLabel }
                            .size(18.dp)
                            .border(2.dp, colors.accentInk, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(6.dp).background(colors.accentInk, CircleShape))
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (position == PositionState.Denied) {
                    Column {
                        Text(stringResource(R.string.bipe_app_location_permission), style = MaterialTheme.typography.bodySmall, color = colors.trustCautionInk)
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) {
                            Text(stringResource(R.string.bipe_contacts_open_settings_android))
                        }
                    }
                }
                if (sendFailed) {
                    Text(stringResource(R.string.bipe_app_location_send_failed), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
                }
                if (liveFailed) {
                    Text(stringResource(R.string.bipe_app_location_live_start_failed), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
                }
                Text(stringResource(R.string.bipe_app_location_drag_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                BipeButton(
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // Action send: m.location through Timeline.sendLocation, asset sender at the own position, else pin.
                        val sdkTimeline = timeline.timeline
                        if (sdkTimeline == null) {
                            sendFailed = true
                            return@BipeButton
                        }
                        val own = ownLocation?.takeIf { isAtOwnPosition }
                        val geoUri = GeoUri.make(
                            own?.latitude ?: centre.latitude,
                            own?.longitude ?: centre.longitude,
                            own?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                        )
                        isSending = true
                        sendFailed = false
                        scope.launch {
                            runCatching {
                                sdkTimeline.sendLocation(
                                    body = context.getString(R.string.bipe_app_location_body, geoUri),
                                    geoUri = geoUri,
                                    description = null,
                                    zoomLevel = centre.zoom.toUByte(),
                                    assetType = if (own != null) AssetType.SENDER else AssetType.PIN,
                                    repliedToEventId = replyToEventId,
                                )
                            }.onSuccess { onClose() }.onFailure { sendFailed = true }
                            isSending = false
                        }
                    },
                ) {
                    if (isSending) {
                        CircularProgressIndicator(color = colors.inkOnAccent, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            stringResource(
                                if (isAtOwnPosition && ownLocation != null) R.string.bipe_app_location_send_current else R.string.bipe_app_location_send_pin,
                            ),
                        )
                    }
                }
                // Spec location-send action startLive; a chat that already gets this account's share shows the banner instead.
                if (liveShares.none { it.roomId == timeline.roomId && it.userId == session.userId }) {
                    BipeOutlinedButton(
                        enabled = !isSending && position != PositionState.Denied,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (LiveLocationCenter.hasPermission(context)) choosingDuration = true else request() },
                    ) {
                        Text(stringResource(R.string.bipe_app_location_live_start))
                    }
                    Text(stringResource(R.string.bipe_app_location_live_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
        }
    }
    if (choosingDuration) {
        AlertDialog(
            onDismissRequest = { choosingDuration = false },
            title = { Text(stringResource(R.string.bipe_app_location_live_duration_title)) },
            text = {
                Column {
                    BipeRegistries.MAPS_LIVE_DURATIONS_MINUTES.forEach { minutes ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                choosingDuration = false
                                val room = timeline.matrixRoom
                                if (room == null) {
                                    liveFailed = true
                                    return@TextButton
                                }
                                isSending = true
                                liveFailed = false
                                scope.launch {
                                    // Action startLive: Room.startLiveLocationShare, then the screen closes and the banner shows.
                                    runCatching { LiveLocationCenter.start(context, session, room, minutes) }
                                        .onSuccess { onClose() }
                                        .onFailure { liveFailed = true }
                                    isSending = false
                                }
                            },
                        ) {
                            Text(LiveLocationRule.durationLabel(context, minutes))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosingDuration = false }) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
}
