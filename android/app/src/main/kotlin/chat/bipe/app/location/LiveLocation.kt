// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.bipe.app.MainActivity
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room as MatrixRoom

/** Type OwnShare of spec live-location-banner: one running live location share of an account. */
data class OwnLiveShare(val userId: String, val roomId: String, val endsAt: Long)

/** The update rule of spec location-send notes (registries/maps.json live). */
object LiveLocationRule {
    /** The chats.liveLocation setting: metres the phone moves before the next update. */
    const val DISTANCE_KEY = "live_location_distance"

    fun distance(context: Context): Int {
        val saved = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getString(DISTANCE_KEY, null)?.toIntOrNull()
        return saved?.takeIf { it in BipeRegistries.MAPS_LIVE_DISTANCES_METRES } ?: BipeRegistries.MAPS_LIVE_DEFAULT_DISTANCE_METRES
    }

    /**
     * true when a position is due for a room: the first one, or after the floor when the phone moved the setting's
     * distance, or after the ceiling when it moved at all (the system sends positions only after 10 m).
     */
    fun isDue(moved: Float?, sinceLastMs: Long?, sinceTryMs: Long?, distance: Int): Boolean {
        val floor = BipeRegistries.MAPS_LIVE_MIN_INTERVAL_SECONDS * 1000L
        if (sinceTryMs != null && sinceTryMs < floor) return false
        if (moved == null || sinceLastMs == null) return true
        if (sinceLastMs < floor) return false
        return moved >= distance || (sinceLastMs >= BipeRegistries.MAPS_LIVE_MAX_INTERVAL_SECONDS * 1000L && moved >= 1f)
    }

    /** "15 min", "1 h", "8 h" (spec location-send action startLive). */
    fun durationLabel(context: Context, minutes: Int): String =
        if (minutes < 60) context.getString(R.string.bipe_app_location_live_minutes, minutes.toString())
        else context.getString(R.string.bipe_app_location_live_hours, (minutes / 60).toString())
}

/**
 * Live location of this phone (MSC3489, specs location-send and live-location-banner): starts and stops the shares,
 * reads positions from LocationManager (no Google Play services) and sends them with Room.sendLiveLocation.
 * LiveLocationService keeps the app in the foreground while a share runs. Only one account shares at a time.
 * The app keeps no position history: only the room, the account and the end time of each running share.
 */
object LiveLocationCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _shares = MutableStateFlow<List<OwnLiveShare>>(emptyList())
    val shares: StateFlow<List<OwnLiveShare>> = _shares.asStateFlow()

    private var appContext: Context? = null
    private var userId: String? = null
    private val rooms = mutableMapOf<String, MatrixRoom>()
    private val lastSent = mutableMapOf<String, Pair<Location, Long>>()
    private val lastTry = mutableMapOf<String, Long>()
    private val endTimers = mutableMapOf<String, Job>()
    private var latest: Location? = null
    private var flush: Job? = null
    private var listener: LocationListener? = null
    /** Shares that a closed app left behind; stopped when their account opens (spec location-send notes, end). */
    private var stale: MutableList<OwnLiveShare>? = null

    private const val STORE = "bipe_live_location"
    private const val STORE_KEY = "shares"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun share(roomId: String, userId: String): OwnLiveShare? = _shares.value.firstOrNull { it.roomId == roomId && it.userId == userId }

    /** Action startLive of spec location-send. Called from the open app, so the foreground service may start. */
    suspend fun start(context: Context, session: AccountSession, room: MatrixRoom, minutes: Int) {
        val app = context.applicationContext
        appContext = app
        userId?.takeIf { it != session.userId }?.let { stopAll(it) }
        val roomId = room.id()
        room.startLiveLocationShare((minutes * 60_000L).toULong())
        userId = session.userId
        rooms[roomId] = room
        lastSent.remove(roomId)
        lastTry.remove(roomId)
        val share = OwnLiveShare(session.userId, roomId, System.currentTimeMillis() + minutes * 60_000L)
        _shares.value = _shares.value.filter { it.roomId != roomId } + share
        staleList(app).removeAll { it.roomId == roomId }
        save(app)
        endTimers.remove(roomId)?.cancel()
        endTimers[roomId] = scope.launch {
            delay((share.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            stop(roomId)
        }
        LiveLocationService.start(app)
        listen(app)
        latest?.let { consider(it) }
    }

    /** Action stop of spec live-location-banner for one chat; also the end of the duration. */
    suspend fun stop(roomId: String) {
        if (_shares.value.none { it.roomId == roomId }) return
        _shares.value = _shares.value.filter { it.roomId != roomId }
        appContext?.let(::save)
        endTimers.remove(roomId)?.cancel()
        lastSent.remove(roomId)
        lastTry.remove(roomId)
        val room = rooms.remove(roomId)
        if (_shares.value.isEmpty()) finish()
        runCatching { room?.stopLiveLocationShare() }
    }

    /** Stops every share of an account: the chat list row, the notification, sign-out and account switch. */
    suspend fun stopAll(userId: String) {
        _shares.value.filter { it.userId == userId }.forEach { stop(it.roomId) }
    }

    fun stopAllSoon(userId: String) {
        scope.launch { stopAll(userId) }
    }

    /** A switch to another account stops the shares of the account before it (spec location-send notes, end). */
    fun accountChanged(userId: String) {
        val current = this.userId ?: return
        if (current != userId) stopAllSoon(current)
    }

    /** Stops the shares that a closed app left for this account, while they still run on the server. */
    suspend fun cleanUp(context: Context, session: AccountSession) {
        val all = staleList(context)
        val left = all.filter { it.userId == session.userId }
        if (left.isEmpty()) return
        all.removeAll(left)
        save(context)
        left.filter { it.endsAt > System.currentTimeMillis() }.forEach { share ->
            runCatching { session.client.getRoom(share.roomId)?.stopLiveLocationShare() }
        }
    }

    private fun finish() {
        val app = appContext
        listener?.let { app?.getSystemService(LocationManager::class.java)?.removeUpdates(it) }
        listener = null
        flush?.cancel()
        flush = null
        latest = null
        userId = null
        app?.let(LiveLocationService::stop)
    }

    @SuppressLint("MissingPermission")
    private fun listen(context: Context) {
        if (listener != null || !hasPermission(context)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val enabled = manager.getProviders(true)
        val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in enabled } ?: return
        val next = LocationListener { location ->
            latest = location
            scope.launch { consider(location) }
        }
        listener = next
        runCatching {
            manager.requestLocationUpdates(provider, BipeRegistries.MAPS_LIVE_MIN_INTERVAL_SECONDS * 1000L, 10f, next, Looper.getMainLooper())
        }.onFailure { listener = null }
    }

    /** Sends the position to each room where it is due; plans one more try for a room that must wait. */
    private suspend fun consider(location: Location) {
        val now = System.currentTimeMillis()
        _shares.value.filter { it.endsAt <= now }.forEach { stop(it.roomId) }
        val context = appContext ?: return
        val distance = LiveLocationRule.distance(context)
        val floor = BipeRegistries.MAPS_LIVE_MIN_INTERVAL_SECONDS * 1000L
        var nextCheck: Long? = null
        for (share in _shares.value) {
            val last = lastSent[share.roomId]
            val due = LiveLocationRule.isDue(
                moved = last?.first?.distanceTo(location),
                sinceLastMs = last?.let { now - it.second },
                sinceTryMs = lastTry[share.roomId]?.let { now - it },
                distance = distance,
            )
            if (due) {
                if (!send(location, share.roomId)) nextCheck = floor
            } else if (last != null && last.first.distanceTo(location) >= distance) {
                val wait = (floor - (now - last.second)).coerceAtLeast(1_000)
                nextCheck = minOf(nextCheck ?: wait, wait)
            }
        }
        val waitMs = nextCheck ?: return
        flush?.cancel()
        flush = scope.launch {
            delay(waitMs)
            latest?.let { consider(it) }
        }
    }

    private suspend fun send(location: Location, roomId: String): Boolean {
        val room = rooms[roomId] ?: return false
        lastTry[roomId] = System.currentTimeMillis()
        val uri = GeoUri.make(location.latitude, location.longitude, location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
        // The first try right after the start can fail until the sync brings the beacon_info back; the next one sends.
        return runCatching { room.sendLiveLocation(uri) }
            .onSuccess { lastSent[roomId] = location to System.currentTimeMillis() }
            .isSuccess
    }

    private fun staleList(context: Context): MutableList<OwnLiveShare> = stale ?: load(
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(STORE_KEY, null),
    ).toMutableList().also { stale = it }

    private fun save(context: Context) {
        val all = _shares.value + staleList(context)
        val json = JSONArray(all.map { JSONObject().put("userId", it.userId).put("roomId", it.roomId).put("endsAt", it.endsAt) })
        val editor = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        if (all.isEmpty()) editor.remove(STORE_KEY) else editor.putString(STORE_KEY, json.toString())
        editor.apply()
    }

    private fun load(text: String?): List<OwnLiveShare> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).map { array.getJSONObject(it) }
            .map { OwnLiveShare(it.getString("userId"), it.getString("roomId"), it.getLong("endsAt")) }
    }.getOrDefault(emptyList())
}

/**
 * Spec location-send notes, background Android: the foreground service of type location while a live share runs,
 * with an ongoing notification and its stop action. The share starts from the open app, so the service needs only the
 * "while in use" location permission, not ACCESS_BACKGROUND_LOCATION.
 */
class LiveLocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LiveLocationCenter.shares.value.map { it.userId }.distinct().forEach(LiveLocationCenter::stopAllSoon)
            stopSelf()
            return START_NOT_STICKY
        }
        if (LiveLocationCenter.shares.value.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val system = getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bipe_app_location_live_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(chat.bipe.app.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.bipe_app_location_live_title))
            .setContentText(getString(R.string.bipe_app_location_live_sharing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.bipe_app_location_live_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "live_location"
        private const val NOTIFICATION_ID = 7_101
        private const val ACTION_STOP = "chat.bipe.app.liveLocation.STOP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
    }
}
