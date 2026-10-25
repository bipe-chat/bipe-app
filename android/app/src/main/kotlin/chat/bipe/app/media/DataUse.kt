// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import chat.bipe.shared.generated.BipeRegistries
import java.time.LocalDate

/**
 * Spec data-use (bipe-chat/bipe#242): counts the media Bipe sends and downloads on this phone, by mobile data and
 * Wi-Fi, today and this calendar month, and says when the app saves mobile data. The counters stay on the phone.
 */
object DataUseMeter {
    /** The data saver switch (spec data-use, state dataSaver). */
    const val SAVER_KEY = "saver"

    data class Count(val sentBytes: Long = 0, val receivedBytes: Long = 0)

    data class Snapshot(val todayMobile: Count, val todayWifi: Count, val monthMobile: Count, val monthWifi: Count)

    private const val FILE = "bipe_data_use"
    private const val COUNTED_KEY = "counted"
    private const val COUNTED_MAX = 2000

    private var prefs: SharedPreferences? = null
    private var connectivity: ConnectivityManager? = null
    @Volatile private var isCellular = false
    private val lock = Any()
    private var counted: ArrayDeque<String> = ArrayDeque()
    private var prunedFor: String? = null

    /** Called once from BipeApplication.onCreate. Before it, nothing is counted. */
    fun start(context: Context) {
        val app = context.applicationContext
        val store = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = store
            counted = ArrayDeque(store.getString(COUNTED_KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
        }
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = manager
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            override fun onLost(network: Network) {
                isCellular = false
            }
        })
    }

    fun saverOn(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SAVER_KEY, BipeRegistries.DATA_SAVER_DEFAULT)

    fun setSaver(context: Context, isOn: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SAVER_KEY, isOn).apply()
    }

    /** true when the system Data Saver is on for Bipe (spec data-use, state systemSaverOn). */
    val systemSaverOn: Boolean
        get() = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    /** On mobile data with the switch or the system saver on: photos and stickers wait for a tap (spec timeline-media). */
    fun savesData(context: Context): Boolean = isCellular && (saverOn(context) || systemSaverOn)

    /** An upload Bipe started. */
    fun recordSent(bytes: Long) = add(bytes, "sent")

    /** A download of [url], counted once per media URL. */
    fun recordReceived(bytes: Long, url: String) {
        val isNew = synchronized(lock) {
            val store = prefs ?: return
            if (url in counted) return@synchronized false
            counted.addLast(url)
            while (counted.size > COUNTED_MAX) counted.removeFirst()
            store.edit().putString(COUNTED_KEY, counted.joinToString("\n")).apply()
            true
        }
        if (isNew) add(bytes, "received")
    }

    fun snapshot(today: LocalDate = LocalDate.now()): Snapshot = synchronized(lock) {
        Snapshot(
            todayMobile = count(day(today), "mobile"),
            todayWifi = count(day(today), "wifi"),
            monthMobile = count(month(today), "mobile"),
            monthWifi = count(month(today), "wifi"),
        )
    }

    private fun add(bytes: Long, field: String, today: LocalDate = LocalDate.now()) {
        if (bytes <= 0) return
        synchronized(lock) {
            val store = prefs ?: return
            val network = if (isCellular) "mobile" else "wifi"
            val edit = store.edit()
            for (period in listOf(day(today), month(today))) {
                val key = "$period.$network.$field"
                edit.putLong(key, store.getLong(key, 0) + bytes)
            }
            if (prunedFor != day(today)) {
                prunedFor = day(today)
                val keep = listOf("${day(today)}.", "${month(today)}.")
                store.all.keys.filter { (it.startsWith("d") || it.startsWith("m")) && it.contains('.') && keep.none(it::startsWith) }
                    .forEach { edit.remove(it) }
            }
            edit.apply()
        }
    }

    private fun count(period: String, network: String): Count {
        val store = prefs ?: return Count()
        return Count(store.getLong("$period.$network.sent", 0), store.getLong("$period.$network.received", 0))
    }

    private fun day(date: LocalDate) = "d%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)

    private fun month(date: LocalDate) = "m%04d%02d".format(date.year, date.monthValue)
}
