// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.company.BipeBrandMark
import chat.bipe.shared.company.BipeBrandMarks
import chat.bipe.shared.company.BipeBrandMarksState
import chat.bipe.shared.company.BipeCnpj
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.generated.BipeScreen

/** The session's brand marks for rows that have no [PeopleService] (spec brand-mark). null: no mark shows. */
val LocalBipeBrandMarks = staticCompositionLocalOf<BipeBrandMarks?> { null }

/** The fixed words of the mark with the brand's name. null for a label this build does not know. */
fun BipeBrandMark.line(context: Context): String? = when (label) {
    "revendedora" -> context.getString(R.string.bipe_brand_mark_label_revendedora, brandName)
    "revendedor" -> context.getString(R.string.bipe_brand_mark_label_revendedor, brandName)
    "corretor" -> context.getString(R.string.bipe_brand_mark_label_corretor, brandName)
    "representante" -> context.getString(R.string.bipe_brand_mark_label_representante, brandName)
    else -> null
}

/** Spec brand-mark: the first mark's line, then "+N" for the others. */
fun List<BipeBrandMark>.summary(context: Context): String? {
    val first = firstOrNull()?.line(context) ?: return null
    return if (size > 1) "$first ${context.getString(R.string.bipe_brand_mark_more, (size - 1).toString())}" else first
}

@Composable
private fun brandMarksOf(marks: BipeBrandMarks?, userId: String?): BipeBrandMarksState {
    if (marks == null || userId == null) return BipeBrandMarksState()
    val states by marks.states.collectAsState()
    return remember(states, userId) { marks.state(userId) }
}

/**
 * Spec brand-mark, person-profile: the line next to a reseller's own name, or the ended notice once. Never a seal icon
 * on the reseller (ADR 0108 point 3). A tap opens the sheet.
 */
@Composable
fun BrandMarkLine(userId: String, marks: BipeBrandMarks, seals: BipeCompanySeals) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val state = brandMarksOf(marks, userId)
    var showsSheet by remember { mutableStateOf(false) }
    val summary = state.marks.summary(context)
    if (summary != null) {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.bipe_brand_mark_title)) { showsSheet = true },
        )
    } else {
        state.endedBrand?.let { MarkLine(PhosphorIcon.INFO, stringResource(R.string.bipe_brand_mark_ended, it), colors.trustCautionInk) }
    }
    if (showsSheet) BrandMarkDialog(state.marks, seals) { showsSheet = false }
}

/** Spec brand-mark, chat header: the summary or the ended notice as text, inside the title that opens person-profile. */
@Composable
fun BrandMarkHeaderLine(userId: String?, marks: BipeBrandMarks?) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val state = brandMarksOf(marks, userId)
    val summary = state.marks.summary(context)
    val ended = state.endedBrand
    when {
        summary != null -> Text(summary, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ended != null -> Text(
            stringResource(R.string.bipe_brand_mark_ended, ended),
            style = MaterialTheme.typography.labelSmall,
            color = colors.trustCautionInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Spec brand-mark, chat list row: the summary on one short line. The row opens the chat. */
@Composable
fun BrandMarkCaption(userId: String?) {
    val context = LocalContext.current
    val summary = brandMarksOf(LocalBipeBrandMarks.current, userId).marks.summary(context) ?: return
    Text(summary, style = MaterialTheme.typography.labelSmall, color = BipeTheme.colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Spec brand-mark: each mark with its brand's own seal, and what the mark does not mean. */
@Composable
fun BrandMarkDialog(marks: List<BipeBrandMark>, seals: BipeCompanySeals, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val sealStates by seals.states.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bipe_brand_mark_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(BipeScreen.BRAND_MARK.id)) {
                    marks.forEach { mark ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            mark.line(context)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary) }
                            Text(stringResource(R.string.bipe_brand_mark_brand_seal), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                            // brandSeal verified: the brand's own cnpj badge verifies and names the mark's CNPJ.
                            val seal = remember(sealStates, mark.brand) { seals.seal(mark.brand) }
                            if (seal is BipeCompanySealState.Verified && seal.payload.cnpj == mark.cnpj) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(painterResource(PhosphorIcon.SEAL_CHECK.drawable), contentDescription = null, tint = colors.trustVerifiedInk, modifier = Modifier.size(16.dp))
                                    Text(
                                        stringResource(R.string.bipe_badge_company_cnpj) + " " + BipeCnpj.formatted(mark.cnpj),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.inkPrimary,
                                    )
                                }
                            } else {
                                Text(stringResource(R.string.bipe_brand_mark_brand_unchecked), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                            }
                        }
                    }
                    Text(stringResource(R.string.bipe_brand_mark_body), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) } },
        containerColor = colors.surfaceRaised,
    )
}
