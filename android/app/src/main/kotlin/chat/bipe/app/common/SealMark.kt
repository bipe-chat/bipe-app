// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.company.BipeSealMark

/** The session's seal store. null outside a session: no seal shows. */
val LocalBipeCompanySeals = staticCompositionLocalOf<BipeCompanySeals?> { null }

/** The mark of [userId] from the seal store, verified on the device. Recomposes when the store answers. */
@Composable
fun sealMarkOf(userId: String?, seals: BipeCompanySeals? = LocalBipeCompanySeals.current): BipeSealMark? {
    if (userId == null || seals == null) return null
    val states by seals.states.collectAsState()
    val persons by seals.personSeals.collectAsState()
    // Keyed on the maps: every answer of the store reads the cache again, which also queues a stale entry.
    return remember(states, persons, userId) { seals.mark(userId) }
}

/** The compact seal next to a name (spec person-profile notes, bipe#596): the same mark in every place. */
@Composable
fun SealMark(userId: String?, modifier: Modifier = Modifier, seals: BipeCompanySeals? = LocalBipeCompanySeals.current, size: Dp = 16.dp) {
    val mark = sealMarkOf(userId, seals) ?: return
    val colors = BipeTheme.colors
    val (icon, tint, label) = when (mark) {
        BipeSealMark.PERSON -> Triple(PhosphorIcon.SEAL_CHECK, colors.trustVerifiedInk, R.string.bipe_badge_verified)
        BipeSealMark.COMPANY -> Triple(PhosphorIcon.SEAL_CHECK, colors.trustVerifiedInk, R.string.bipe_badge_company)
        BipeSealMark.PERSON_SUSPENDED -> Triple(PhosphorIcon.SEAL_WARNING, colors.trustCautionInk, R.string.bipe_badge_suspended)
        BipeSealMark.COMPANY_SUSPENDED -> Triple(PhosphorIcon.SEAL_WARNING, colors.trustCautionInk, R.string.bipe_badge_company_suspended)
    }
    Icon(painterResource(icon.drawable), contentDescription = stringResource(label), tint = tint, modifier = modifier.size(size))
}
