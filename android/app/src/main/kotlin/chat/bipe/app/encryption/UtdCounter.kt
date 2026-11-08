// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.content.Context
import chat.bipe.shared.settings.BipeReportFile
import java.time.Instant
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.UnableToDecryptDelegate
import org.matrix.rustcomponents.sdk.UnableToDecryptInfo

/**
 * The unable-to-decrypt hook (bipe-chat/bipe#1122): counts on this phone each message the SDK could not decrypt, by
 * cause. It keeps no event ID, room ID, sender or text, and sends nothing. The counts go into a problem report as
 * utd.json, only when the person includes the logs (spec problem-report).
 */
class UtdCounter(context: Context) : UnableToDecryptDelegate {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onUtd(info: UnableToDecryptInfo) {
        synchronized(LOCK) {
            val counts = JSONObject(prefs.getString(COUNTS, null) ?: "{}")
            val cause = info.cause.name
            counts.put(cause, counts.optInt(cause) + 1)
            val edit = prefs.edit().putString(COUNTS, counts.toString()).putString(LAST, Instant.now().toString())
            // A late decryption: the SDK decrypted it after the grace time, so the person saw it locked for a while.
            if (info.timeToDecryptMs != null) edit.putInt(LATE, prefs.getInt(LATE, 0) + 1)
            edit.apply()
        }
    }

    companion object {
        private const val PREFS = "bipe_utd"
        private const val COUNTS = "counts"
        private const val LATE = "late"
        private const val LAST = "last"
        private val LOCK = Any()

        /** utd.json for the problem report: totals per cause on this phone, the late ones and the last time. */
        fun reportFile(context: Context): BipeReportFile = synchronized(LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val counts = JSONObject(prefs.getString(COUNTS, null) ?: "{}")
            var total = 0
            counts.keys().forEach { total += counts.optInt(it) }
            val report = JSONObject()
                .put("causes", counts)
                .put("total", total)
                .put("late", prefs.getInt(LATE, 0))
            prefs.getString(LAST, null)?.let { report.put("last", it) }
            BipeReportFile("utd.json", "application/json", report.toString().encodeToByteArray())
        }
    }
}
