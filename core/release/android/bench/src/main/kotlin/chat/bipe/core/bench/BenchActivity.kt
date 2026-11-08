// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.bench

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import chat.bipe.core.harness.BadgeHarness

/**
 * bipe-chat/bipe#1277 item 8: runs the vectors through the release AAR on this phone, then times 1000 verifications
 * of one badge. The screen and logcat (tag BipeCoreBench) show the result line and the verdict against the budget
 * of 5 ms p95. A wrong vector result shows FAILED. The run is on a background thread; the screen updates at the end.
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 16f; text = RUNNING }
        setContentView(view)
        Thread {
            val report = runCatching {
                val line = BadgeHarness.run(assets.open("badges.json").bufferedReader().readText())
                val p95 = Regex("\"p95_us\":([0-9.Ee+-]+)").find(line)!!.groupValues[1].toDouble() / 1000
                val verdict = if (p95 <= BUDGET_MS) "WITHIN BUDGET" else "OVER BUDGET"
                "$verdict: p95 %.3f ms (budget %.0f ms)\n\n%s".format(p95, BUDGET_MS, line)
            }.getOrElse { "FAILED: $it" }
            val result = "$report\n\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.first()}, " +
                "bipe-core ${packageManager.getPackageInfo(packageName, 0).versionName}"
            Log.i("BipeCoreBench", result.replace('\n', ' '))
            runOnUiThread { view.text = result }
        }.start()
    }

    private companion object {
        const val BUDGET_MS = 5.0

        // A diagnostic screen for the phone run, in English like the logcat line: no product text.
        const val RUNNING = "bipe-core: running"
    }
}
