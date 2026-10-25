// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBrandMarkCode
import chat.bipe.shared.api.BipeBrandMarkInvite
import chat.bipe.shared.api.BipeBrandMarkInvitePreview
import chat.bipe.shared.api.BipeCreatedBrandMarkInvite
import chat.bipe.shared.api.BipeGrantedBrandMark
import chat.bipe.shared.api.BipeGrantedBrandMarks
import chat.bipe.shared.api.BipeHeldBrandMark
import chat.bipe.shared.api.acceptBrandMark
import chat.bipe.shared.api.brandMarkInvite
import chat.bipe.shared.api.cancelBrandMarkInvite
import chat.bipe.shared.api.createBrandMarkInvite
import chat.bipe.shared.api.endBrandMark
import chat.bipe.shared.api.grantedBrandMarks
import chat.bipe.shared.api.heldBrandMarks
import chat.bipe.shared.api.refuseBrandMark
import chat.bipe.shared.company.BipeCnpj
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What went wrong on brand-marks; codeInvalid never says why (spec brand-marks error). */
private enum class BrandMarksProblem(val text: Int) {
    CODE_INVALID(R.string.bipe_brand_marks_code_invalid),
    LIMIT(R.string.bipe_brand_marks_limit),
    ALREADY_HELD(R.string.bipe_brand_marks_already_held),
    OWN_BRAND(R.string.bipe_brand_marks_own_brand),
    NOT_VERIFIED(R.string.bipe_brand_marks_not_verified),
    FAILED(R.string.bipe_brand_marks_failed),
    ;

    companion object {
        fun of(error: Exception): BrandMarksProblem = when {
            error !is BipeApiException.Server -> FAILED
            error.code == BipeApiErrorCode.NOT_FOUND -> CODE_INVALID
            error.code == BipeApiErrorCode.BRAND_MARK_LIMIT -> LIMIT
            error.code == BipeApiErrorCode.BAD_REQUEST && error.reason == "already_held" -> ALREADY_HELD
            error.code == BipeApiErrorCode.BAD_REQUEST && error.reason == "own_brand" -> OWN_BRAND
            error.code == BipeApiErrorCode.BRAND_NOT_VERIFIED -> NOT_VERIFIED
            else -> FAILED
        }
    }
}

/** The fixed words of a mark with the brand's name (registries/brand-marks.json). */
fun brandMarkLine(context: Context, label: String, brandName: String): String = when (label) {
    "revendedora" -> context.getString(R.string.bipe_brand_mark_label_revendedora, brandName)
    "revendedor" -> context.getString(R.string.bipe_brand_mark_label_revendedor, brandName)
    "corretor" -> context.getString(R.string.bipe_brand_mark_label_corretor, brandName)
    "representante" -> context.getString(R.string.bipe_brand_mark_label_representante, brandName)
    else -> brandName
}

private class Offer(val code: String, val preview: BipeBrandMarkInvitePreview)

/**
 * Spec brand-marks (ADR 0162, bipe-chat/bipe#486): on the Trabalho tab, a reseller accepts, refuses and removes a
 * brand's mark; a brand with a verified claim makes codes and ends the marks it gave.
 */
@Composable
fun BrandMarksScreen(business: BusinessApi, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf<List<BipeHeldBrandMark>>(emptyList()) }
    var granted by remember { mutableStateOf(BipeGrantedBrandMarks(emptyList(), emptyList())) }
    val resellerNames = remember { mutableStateMapOf<String, String>() }
    var brandName by remember { mutableStateOf<String?>(null) }
    var codeText by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf<Offer?>(null) }
    var label by remember { mutableStateOf(BipeRegistries.BRAND_MARK_LABELS.firstOrNull().orEmpty()) }
    var created by remember { mutableStateOf<BipeCreatedBrandMarkInvite?>(null) }
    var problem by remember { mutableStateOf<BrandMarksProblem?>(null) }
    var working by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BipeHeldBrandMark?>(null) }
    var ending by remember { mutableStateOf<BipeGrantedBrandMark?>(null) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        try {
            held = business.withToken { heldBrandMarks(it) }
            granted = business.withToken { grantedBrandMarks(it) }
            val claim = business.withToken { myBusiness(it) }
            brandName = if (claim != null && claim.status == "verified") claim.registry?.takeIf { it.active }?.let { it.tradeName ?: it.legalName } else null
            granted.marks.map { it.reseller }.filter { it !in resellerNames }.forEach { resellerNames[it] = business.displayName(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            problem = BrandMarksProblem.FAILED
        }
    }

    // Runs one call, shows its problem, then reloads the lists.
    fun run(call: suspend () -> Unit) {
        scope.launch {
            working = true
            problem = null
            try {
                call()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = BrandMarksProblem.of(error)
            } finally {
                working = false
                removing = null
                ending = null
                revision++
            }
        }
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.BRAND_MARKS.id)) {
        GroupTopBar(stringResource(R.string.bipe_brand_marks_title), onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.bipe_brand_marks_held_header))
            if (held.isEmpty()) Text(stringResource(R.string.bipe_brand_marks_held_empty), color = colors.inkSecondary)
            held.forEach { mark ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(brandMarkLine(context, mark.label, mark.brandName), color = colors.inkPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { removing = mark }, enabled = !working) { Text(stringResource(R.string.bipe_brand_marks_remove), color = colors.trustDangerInk) }
                }
            }
            Text(stringResource(R.string.bipe_brand_marks_held_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = codeText,
                onValueChange = { codeText = it },
                label = { Text(stringResource(R.string.bipe_brand_marks_code_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            BipeButton(
                onClick = {
                    val code = BipeBrandMarkCode.extract(codeText)
                    if (code == null) problem = BrandMarksProblem.CODE_INVALID
                    else run { offer = Offer(code, business.withToken { brandMarkInvite(code, it) }) }
                },
                enabled = !working && codeText.isNotBlank(),
            ) { Text(stringResource(R.string.bipe_brand_marks_check)) }
            problem?.let { Text(stringResource(it.text), color = colors.trustCautionInk) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.bipe_brand_marks_grant_header))
            val name = brandName
            if (name == null) {
                Text(stringResource(R.string.bipe_brand_marks_not_verified), color = colors.inkSecondary)
            } else {
                Text(stringResource(R.string.bipe_brand_mark_title), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                BipeRegistries.BRAND_MARK_LABELS.forEach { id ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = label == id, role = Role.RadioButton) { label = id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = label == id, onClick = null)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(brandMarkLine(context, id, name), color = colors.inkPrimary)
                    }
                }
                BipeButton(onClick = { run { created = business.withToken { createBrandMarkInvite(label, it) } } }, enabled = !working) {
                    Text(stringResource(R.string.bipe_brand_marks_make_code))
                }
                created?.let { invite -> CreatedCode(context, invite) }
            }
            Text(stringResource(R.string.bipe_brand_marks_grant_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)

            if (granted.invites.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle(stringResource(R.string.bipe_brand_marks_open_codes))
                granted.invites.forEach { invite ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(brandMarkLine(context, invite.label, name.orEmpty()), color = colors.inkPrimary)
                            Text(dateText(invite.expiresAt), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                        TextButton(onClick = { run { business.withToken { cancelBrandMarkInvite(invite.id, it) } } }, enabled = !working) {
                            Text(stringResource(R.string.bipe_brand_marks_cancel_code), color = colors.trustDangerInk)
                        }
                    }
                }
            }
            if (granted.marks.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle(stringResource(R.string.bipe_brand_marks_granted_header))
                granted.marks.forEach { mark ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(resellerNames[mark.reseller] ?: mark.reseller, color = colors.inkPrimary)
                            Text(brandMarkLine(context, mark.label, name.orEmpty()), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                        TextButton(onClick = { ending = mark }, enabled = !working) { Text(stringResource(R.string.bipe_brand_marks_end), color = colors.trustDangerInk) }
                    }
                }
            }
        }
    }

    offer?.let { current ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text(stringResource(R.string.bipe_brand_marks_offer_title, current.preview.brandName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(brandMarkLine(context, current.preview.label, current.preview.brandName), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                    Text(stringResource(R.string.bipe_badge_company_cnpj) + " " + BipeCnpj.formatted(current.preview.cnpj), color = colors.inkSecondary)
                    Text(stringResource(R.string.bipe_brand_marks_brand_sees), color = colors.inkPrimary)
                    Text(stringResource(R.string.bipe_brand_marks_brand_not_sees), color = colors.inkPrimary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    offer = null
                    run {
                        business.withToken { acceptBrandMark(current.code, it) }
                        codeText = ""
                    }
                }) { Text(stringResource(R.string.bipe_brand_marks_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    offer = null
                    run {
                        business.withToken { refuseBrandMark(current.code, it) }
                        codeText = ""
                    }
                }) { Text(stringResource(R.string.bipe_brand_marks_refuse), color = colors.trustDangerInk) }
            },
            containerColor = colors.surfaceRaised,
        )
    }
    removing?.let { mark ->
        ConfirmDialog(stringResource(R.string.bipe_brand_marks_remove_confirm, mark.brandName), stringResource(R.string.bipe_brand_marks_remove), onCancel = { removing = null }) {
            run { business.withToken { endBrandMark(mark.id, it) } }
        }
    }
    ending?.let { mark ->
        ConfirmDialog(stringResource(R.string.bipe_brand_marks_end_confirm, resellerNames[mark.reseller] ?: mark.reseller), stringResource(R.string.bipe_brand_marks_end), onCancel = { ending = null }) {
            run { business.withToken { endBrandMark(mark.id, it) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = BipeTheme.colors.accentInk)
}

@Composable
private fun CreatedCode(context: Context, created: BipeCreatedBrandMarkInvite) {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Text(created.code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = colors.inkPrimary)
        }
        Text(stringResource(R.string.bipe_brand_marks_code_ready, days(created.invite).toString()), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(created.code, created.link))
            }) { Text(stringResource(R.string.bipe_app_copy)) }
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, created.link), null))
            }) { Text(stringResource(R.string.bipe_app_share)) }
        }
    }
}

@Composable
private fun ConfirmDialog(text: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = BipeTheme.colors
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(text, color = colors.inkPrimary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = colors.trustDangerInk) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** The invite's life in whole days, for bipe_brand_marks_code_ready. */
private fun days(invite: BipeBrandMarkInvite): Long = runCatching {
    maxOf(1L, Duration.between(Instant.parse(invite.createdAt), Instant.parse(invite.expiresAt)).toDays())
}.getOrDefault(1L)

private fun dateText(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(iso)
