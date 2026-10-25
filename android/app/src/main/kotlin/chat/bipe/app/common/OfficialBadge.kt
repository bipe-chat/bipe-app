// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R

/**
 * The Bipe badge after the name of an official account (ADR 0117, specs chat-list, chat-timeline, person-profile).
 * Callers show it only when BipeOfficialAccounts.isOfficial(userId) is true.
 */
@Composable
fun OfficialBadge(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelSmall) {
    val colors = BipeTheme.colors
    val label = stringResource(R.string.bipe_badge_official_a11y)
    val shape = RoundedCornerShape(percent = 50)
    Text(
        stringResource(R.string.bipe_badge_official),
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = colors.trustVerifiedInk,
        maxLines = 1,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = label }
            .background(colors.trustVerifiedWash, shape)
            .border(1.dp, colors.trustVerifiedLine, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
