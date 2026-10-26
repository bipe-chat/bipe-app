// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.location

import chat.bipe.shared.generated.BipeRegistries
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/** Type MapCentre of spec location-send: a place and a zoom. */
data class MapPoint(val latitude: Double, val longitude: Double, val zoom: Int) {
    fun withZoom(zoom: Int): MapPoint = copy(zoom = BipeMap.clampZoom(zoom))

    companion object {
        fun of(latitude: Double, longitude: Double, zoom: Int = BipeRegistries.MAPS_DEFAULT_ZOOM): MapPoint =
            MapPoint(latitude, longitude, BipeMap.clampZoom(zoom))

        /** The centre of Brazil at minZoom: the start of location-send until the position comes. */
        val BRAZIL = MapPoint(-14.2, -51.9, BipeRegistries.MAPS_MIN_ZOOM)
    }
}

/** A distance on the screen in points (dp), x right and y down. */
data class MapOffset(val x: Double, val y: Double)

/**
 * Maps from registries/maps.json (ADR 0131): static images and Web Mercator geometry.
 * tileserver-gl renders with 512-point tiles, so the world is 512 * 2^z points wide (spec location-send notes).
 */
object BipeMap {
    private const val MAX_LATITUDE = 85.05112878

    fun clampZoom(zoom: Int): Int = zoom.coerceIn(BipeRegistries.MAPS_MIN_ZOOM, BipeRegistries.MAPS_MAX_ZOOM)

    private fun worldSize(zoom: Int): Double = 512.0 * 2.0.pow(zoom)

    private fun project(latitude: Double, longitude: Double, zoom: Int): MapOffset {
        val size = worldSize(zoom)
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val sine = sin(lat * PI / 180)
        return MapOffset((longitude + 180) / 360 * size, (0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)) * size)
    }

    /** The place under a point that is `offset` away from the view centre. */
    fun place(offset: MapOffset, centre: MapPoint): MapPoint {
        val size = worldSize(centre.zoom)
        val origin = project(centre.latitude, centre.longitude, centre.zoom)
        var longitude = (origin.x + offset.x) / size * 360 - 180
        longitude = ((longitude + 180) % 360 + 360) % 360 - 180
        val n = PI - 2 * PI * (origin.y + offset.y) / size
        val latitude = (atan(sinh(n)) * 180 / PI).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return MapPoint(latitude, longitude, centre.zoom)
    }

    /** Where a place shows, in points from the view centre, on a map centred at `centre` (at the zoom of `centre`). */
    fun offset(place: MapPoint, centre: MapPoint): MapOffset {
        val a = project(place.latitude, place.longitude, centre.zoom)
        val b = project(centre.latitude, centre.longitude, centre.zoom)
        return MapOffset(a.x - b.x, a.y - b.y)
    }

    /** A static map request. */
    data class Request(val url: String, val centre: MapPoint)

    /**
     * A static map request for a view of width x height points. A view larger than maxStaticSize asks for half the
     * size one zoom lower, which covers the same area; the image is then drawn at the view size.
     */
    fun request(centre: MapPoint, widthPoints: Float, heightPoints: Float, dark: Boolean): Request? {
        var width = widthPoints.roundToInt()
        var height = heightPoints.roundToInt()
        if (width <= 0 || height <= 0) return null
        var zoom = centre.zoom
        val limit = BipeRegistries.MAPS_MAX_STATIC_SIZE
        while (max(width, height) > limit && zoom > 0) {
            width = (width + 1) / 2
            height = (height + 1) / 2
            zoom -= 1
        }
        width = min(width, limit)
        height = min(height, limit)
        val path = BipeRegistries.MAPS_STATIC_PATH
            .replace("{style}", if (dark) BipeRegistries.MAPS_STYLE_DARK else BipeRegistries.MAPS_STYLE_LIGHT)
            .replace("{lon}", GeoUri.format(centre.longitude))
            .replace("{lat}", GeoUri.format(centre.latitude))
            .replace("{zoom}", zoom.toString())
            .replace("{width}", width.toString())
            .replace("{height}", height.toString())
        return Request(BipeRegistries.MAPS_BASE_URL + path, centre)
    }
}

/** RFC 5870 geo URIs of m.location (spec location-view notes). */
object GeoUri {
    /** Decimal degrees with coordinateDecimals digits, always with a dot. */
    fun format(value: Double): String = String.format(Locale.ROOT, "%.${BipeRegistries.MAPS_COORDINATE_DECIMALS}f", value)

    /** 'geo:<lat>,<lon>' and ';u=<metres>' when the uncertainty is known. */
    fun make(latitude: Double, longitude: Double, uncertainty: Double? = null): String {
        val uri = "geo:${format(latitude)},${format(longitude)}"
        return if (uncertainty != null && uncertainty > 0) "$uri;u=${uncertainty.roundToInt()}" else uri
    }

    /** Latitude and longitude, or null when the URI is not a geo URI with valid WGS 84 coordinates. */
    fun parse(uri: String): Pair<Double, Double>? {
        val text = uri.trim()
        if (!text.lowercase(Locale.ROOT).startsWith("geo:")) return null
        val plain = text.substring(4).substringBefore(';').substringBefore('?')
        val parts = plain.split(',')
        if (parts.size !in 2..3) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
