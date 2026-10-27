// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.location

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LiveLocationContent

/** Type LiveShare of spec location-view: a live location row from MsgLikeKind.LiveLocation. */
data class LiveLocationMessage(val location: LocationMessage?, val endsAt: Long, val isLiveFlag: Boolean) {
    fun isLive(now: Long = System.currentTimeMillis()): Boolean = isLiveFlag && now < endsAt

    /** bipe_app_location_live_until with the end time, else bipe_app_location_live_ended. */
    fun statusLine(context: Context, now: Long = System.currentTimeMillis()): String =
        if (isLive(now)) context.getString(R.string.bipe_app_location_live_until, DateFormat.getTimeFormat(context).format(Date(endsAt)))
        else context.getString(R.string.bipe_app_location_live_ended)

    companion object {
        fun of(content: LiveLocationContent): LiveLocationMessage = LiveLocationMessage(
            location = content.locations.lastOrNull()?.let { LocationMessage.of(it.geoUri, it.description) },
            endsAt = (content.ts + content.timeoutMs).toLong(),
            isLiveFlag = content.isLive,
        )
    }
}

/** The time now, again each minute: live rows and bars check the end time with it. */
@Composable
private fun minuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Spec location-view, live row: the map follows the newest beacon; the status line checks the end each minute. */
@Composable
fun TimelineLiveLocationRow(live: LiveLocationMessage, onOpenFull: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val now = minuteClock()
    val title = stringResource(R.string.bipe_app_location_live_title)
    Column(Modifier.width(260.dp).testTag(BipeScreen.LOCATION_VIEW.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceSunken)
                .clickable(enabled = live.location != null, onClickLabel = title, onClick = onOpenFull)
                .semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            val location = live.location
            if (location != null) {
                StaticMap(location.point, Modifier.fillMaxSize())
                MapPin(Modifier.alpha(if (live.isLive(now)) 1f else 0.5f))
            } else {
                Text(
                    stringResource(R.string.bipe_app_location_live_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.inkPrimary)
        Text(live.statusLine(context, now), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

/** Spec live-location-banner: the bar above the timeline while this account shares its location in the chat. */
@Composable
fun LiveLocationChatBar(roomId: String, userId: String) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shares by LiveLocationCenter.shares.collectAsState()
    val share = shares.firstOrNull { it.roomId == roomId && it.userId == userId } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentWash)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(BipeScreen.LIVE_LOCATION_BANNER.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapPin()
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.bipe_app_location_live_sharing),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
            )
            Text(
                stringResource(R.string.bipe_app_location_live_until, DateFormat.getTimeFormat(context).format(Date(share.endsAt))),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
        }
        BipeOutlinedButton(onClick = { scope.launch { LiveLocationCenter.stop(roomId) } }) {
            Text(stringResource(R.string.bipe_app_location_live_stop))
        }
    }
}

/** Spec live-location-banner: the row above the chat list while this account shares; stop ends every share. */
@Composable
fun LiveLocationListRow(userId: String) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val shares by LiveLocationCenter.shares.collectAsState()
    if (shares.none { it.userId == userId }) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentWash)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(BipeScreen.LIVE_LOCATION_BANNER.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapPin()
        Text(
            stringResource(R.string.bipe_app_location_live_sharing),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        BipeOutlinedButton(onClick = { scope.launch { LiveLocationCenter.stopAll(userId) } }) {
            Text(stringResource(R.string.bipe_app_location_live_stop))
        }
    }
}
