// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Spec scam-warning money pause: the seconds left on [rowId], redrawn once a second while it runs. */
@Composable
fun rememberPauseSecondsLeft(model: ChatTimelineModel, rowId: String): Int {
    val pause by model.moneyPause.collectAsState()
    var left by remember(rowId) { mutableIntStateOf(pause.secondsLeft(rowId)) }
    LaunchedEffect(pause, rowId) {
        left = pause.secondsLeft(rowId)
        while (left > 0) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            left = pause.secondsLeft(rowId)
        }
    }
    return left
}
