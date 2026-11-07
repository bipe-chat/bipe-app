// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.core.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.sdkGitSha

/** The AAR on a device, with libmatrix_sdk_ffi.so loaded in the same process (bipe-chat/bipe#1172). */
@RunWith(AndroidJUnit4::class)
class BadgeHarnessTest {
    @Test
    fun vectorsMetricsAndMatrixSdk() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("badges.json").bufferedReader().use { it.readText() }
        // Between the two runs of the core: load the SDK library and call it.
        val metrics = BadgeHarness.run(json) { assertTrue(sdkGitSha().isNotEmpty()) }
        println(metrics)
    }
}
