// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.location.GeoUri
import chat.bipe.app.location.MapPoint
import chat.bipe.shared.generated.BipeRegistries
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind

data class DesktopLocation(val point: MapPoint?, val pinned: Boolean, val description: String?, val live: Live? = null) {
    data class Live(val endsAt: Long, val active: Boolean)

    companion object {
        internal fun of(kind: MsgLikeKind): DesktopLocation? = when (kind) {
            is MsgLikeKind.Message -> {
                val event = (kind.content.msgType as? MessageType.Location)?.content
                event?.let { data ->
                    GeoUri.parse(data.geoUri)?.let { (lat, lon) ->
                        DesktopLocation(MapPoint.of(lat, lon, data.zoomLevel?.toInt() ?: BipeRegistries.MAPS_DEFAULT_ZOOM),
                            data.asset == AssetType.PIN, data.description?.takeIf { it.isNotBlank() })
                    }
                }
            }
            is MsgLikeKind.LiveLocation -> {
                val data = kind.content
                val latest = data.locations.lastOrNull()
                val point = latest?.geoUri?.let(GeoUri::parse)?.let { (lat, lon) -> MapPoint.of(lat, lon) }
                val timeout = data.timeoutMs.coerceAtMost(86_400_000uL).toLong()
                val start = data.ts.toLong().coerceIn(0, Long.MAX_VALUE - timeout)
                DesktopLocation(point, data.assetType == AssetType.PIN, latest?.description ?: data.description,
                    Live(start + timeout, data.isLive))
            }
            else -> null
        }
    }
}
