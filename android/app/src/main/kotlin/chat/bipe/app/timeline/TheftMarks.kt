// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeTheftMarkVerifier
import chat.bipe.shared.BipeTheftStatement
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.runCatchingExceptions
import chat.bipe.shared.theftMarks

/**
 * Theft marks of chat partners (spec chat-timeline theftMark, ADR 0044, bipe-chat/bipe#600). The app asks
 * POST /v1/theft-marks for a person at most every checkMinutes per account and verifies the statement with the badge
 * keys (docs/theft-mark.md). A failed call or an invalid statement is no mark.
 */
object TheftMarkChecks {
    private data class Entry(val checkedAtMillis: Long, val statement: BipeTheftStatement?)

    private val entries = mutableMapOf<String, Entry>()
    private val api = BipeApiClient(AppConfig.API_BASE_URL)
    private val verifier: BipeTheftMarkVerifier? by lazy { runCatchingExceptions { BipeTheftMarkVerifier.bipeTrusted() }.getOrNull() }

    const val INTERVAL_MILLIS: Long = BipeRegistries.THEFT_CHECK_MINUTES * 60_000L

    /** The valid statement for [userId], else null. */
    suspend fun statement(userId: String, session: AccountSession, nowMillis: Long = System.currentTimeMillis()): BipeTheftStatement? {
        val key = "${session.userId}|$userId"
        synchronized(entries) { entries[key] }?.let { entry ->
            if (nowMillis - entry.checkedAtMillis < INTERVAL_MILLIS) {
                return entry.statement?.takeIf { it.expiresAtMillis > nowMillis }
            }
        }
        val verifier = verifier ?: return null
        val envelopes = runCatchingExceptions {
            BipeApiClient.withOpenIdRetry(session.openId) { api.theftMarks(listOf(userId), it) }
        }.getOrNull() ?: return null
        val statement = verifier.statement(userId, envelopes, nowMillis)
        synchronized(entries) { entries[key] = Entry(nowMillis, statement) }
        return statement
    }
}

/** Spec chat-timeline theftMark: the red bar above the composer of a 1:1 chat with a marked account. */
@Composable
fun TheftMarkBanner(name: String) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.trustDangerWash, RoundedCornerShape(12.dp))
            .border(2.dp, colors.trustDangerLine, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.bipe_theft_banner_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = colors.trustDangerInk)
            Text(stringResource(R.string.bipe_theft_banner_body, name), style = MaterialTheme.typography.bodySmall, color = colors.inkPrimary)
        }
    }
}

/** Spec chat-timeline theftMark: the line under a message the marked account sent near or after the theft. */
@Composable
fun TheftMessageMarker() {
    val colors = BipeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.bipe_theft_message_marker), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
    }
}
