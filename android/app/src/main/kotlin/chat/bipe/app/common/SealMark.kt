// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.PersonSealDialog
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.company.BipeSealMark
import chat.bipe.shared.company.localizedName
import chat.bipe.shared.generated.BipeRegistries

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
    // The icon carries the level, never the colour alone (ADR 0275).
    val (icon, tint, label) = when (mark) {
        BipeSealMark.PERSON_BANK -> Triple(PhosphorIcon.SEAL_CHECK, colors.trustVerifiedInk, personLabel("bank"))
        BipeSealMark.PERSON_STATE -> Triple(PhosphorIcon.SEAL_CHECK_FILL, colors.trustVerifiedInk, personLabel("state"))
        BipeSealMark.COMPANY -> Triple(PhosphorIcon.SEAL_CHECK, colors.trustVerifiedInk, stringResource(R.string.bipe_badge_company))
        BipeSealMark.PERSON_SUSPENDED -> Triple(PhosphorIcon.SEAL_WARNING, colors.trustCautionInk, stringResource(R.string.bipe_badge_suspended))
        BipeSealMark.COMPANY_SUSPENDED -> Triple(PhosphorIcon.SEAL_WARNING, colors.trustCautionInk, stringResource(R.string.bipe_badge_company_suspended))
    }
    Icon(painterResource(icon.drawable), contentDescription = label, tint = tint, modifier = modifier.size(size))
}

/** The apps decode only alias levels, and each assurance has one alias scheme (registries/schemes.json). */
@Composable
private fun personLabel(assurance: String): String {
    val scheme = BipeRegistries.personSchemes.firstOrNull { it.assurance == assurance }
        ?: return stringResource(R.string.bipe_badge_verified)
    return stringResource(R.string.bipe_badge_verified_via, scheme.localizedName(LocalContext.current))
}

/**
 * The compact seal where a tap opens person-seal for the strongest proof (ADR 0275 point 4): the profile and the chat
 * header. Any other mark, an amber seal too, stays a plain mark.
 */
@Composable
fun PersonSealButton(userId: String?, modifier: Modifier = Modifier, seals: BipeCompanySeals? = LocalBipeCompanySeals.current, size: Dp = 16.dp) {
    sealMarkOf(userId, seals)
    val strongest = if (userId != null) seals?.strongestProof(userId) else null
    if (strongest == null) {
        SealMark(userId, modifier, seals, size)
        return
    }
    var opened by remember { mutableStateOf(false) }
    SealMark(userId, modifier.bipeTapTarget().clickable(role = Role.Button) { opened = true }, seals, size)
    if (opened) PersonSealDialog(strongest, onClose = { opened = false })
}
