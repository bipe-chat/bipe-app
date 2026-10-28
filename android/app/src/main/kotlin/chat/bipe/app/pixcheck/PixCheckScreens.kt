// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.PixCheck
import chat.bipe.shared.R
import chat.bipe.shared.ScamWarningKind
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Specs pix-check-receive and pix-check-result: the check, then its result. Works signed out and offline. */
@Composable
fun PixCheckScreen(
    state: PixCheckReceiveModel.State,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    /** Shows Send to a chat (accountAvailable of pix-check-result). */
    accountAvailable: Boolean = false,
    /** Action sendToChat: the original input of a report, for an editable preview. */
    onSendToChat: (PixCheckedItem) -> Unit = {},
) {
    val colors = BipeTheme.colors
    val working = state.phase == PixCheckReceiveModel.Phase.READING || state.phase == PixCheckReceiveModel.Phase.CHECKING
    BackHandler { if (working) onCancel() else onClose() }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.PIX_CHECK_RECEIVE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = if (working) onCancel else onClose) {
                    Text(stringResource(if (working) R.string.bipe_app_cancel else R.string.bipe_pix_check_close), color = colors.accentInk)
                }
                Text(
                    stringResource(R.string.bipe_pix_check_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(end = 64.dp),
                )
            }
            when (state.phase) {
                PixCheckReceiveModel.Phase.READING, PixCheckReceiveModel.Phase.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
                PixCheckReceiveModel.Phase.READY, PixCheckReceiveModel.Phase.NOTHING_FOUND ->
                    PixCheckResult(state.results, accountAvailable, onSendToChat)
                PixCheckReceiveModel.Phase.FAILED -> Message(stringResource(R.string.bipe_error_generic), if (state.canRetry) onRetry else null)
                else -> Message(stringResource(R.string.bipe_pix_check_nothing_found), null)
            }
        }
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = colors.inkPrimary, textAlign = TextAlign.Center)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.bipe_app_retry), color = colors.accentInk) }
        }
        Text(stringResource(R.string.bipe_pix_check_private), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

/** Spec pix-check-result: the fields found, the rule warning, and the bank check at the end. */
@Composable
fun PixCheckResult(results: List<PixCheckedItem>, accountAvailable: Boolean, onSendToChat: (PixCheckedItem) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(BipeScreen.PIX_CHECK_RESULT.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (results.none { it.check.foundPix }) {
            Text(stringResource(R.string.bipe_pix_check_nothing_found), color = colors.inkPrimary)
        }
        results.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.code?.let { CodeReport(it) }
                if (item.keys.isNotEmpty()) KeysReport(item.keys)
                item.warningKind?.let { WarningCard(it) }
                // Action shareResult: works signed out and offline; the person picks the app and the chat.
                val shareText = if (item.check.foundPix) PixShareResult.text(context, item) else null
                if (shareText != null) {
                    TextButton(onClick = {
                        if (!PixShareResult.share(context, shareText)) {
                            Toast.makeText(context, context.getString(R.string.bipe_error_generic), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(stringResource(R.string.bipe_pix_check_share_result), color = colors.accentInk)
                    }
                }
                if (accountAvailable && item.check.foundPix) {
                    TextButton(onClick = { onSendToChat(item) }) {
                        Text(stringResource(R.string.bipe_pix_check_send_to_chat), color = colors.accentInk)
                    }
                }
            }
        }
        Text(stringResource(R.string.bipe_pix_check_private), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        // Always last, also for bad CRC, malformed and nothing found.
        Text(stringResource(R.string.bipe_pix_check_bank_name), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
    }
}

/** A BR Code: the status line, then the fields in the order of the spec notes. */
@Composable
private fun CodeReport(code: PixCheck.Code) {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bipe_pix_check_code_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        when (code.status) {
            PixCheck.Status.BAD_CRC -> {
                // Before any parsed field, with the caution treatment; the fields stay unverified.
                Caution(stringResource(R.string.bipe_pix_check_code_bad_crc))
                Fields(code)
            }
            PixCheck.Status.MALFORMED -> Caution(stringResource(R.string.bipe_pix_check_code_malformed))
            PixCheck.Status.VALID -> {
                if (code.dynamic) {
                    Text(stringResource(R.string.bipe_pix_check_code_dynamic), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
                Fields(code)
                if (!code.dynamic) {
                    Text(stringResource(R.string.bipe_pix_check_name_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            }
        }
    }
}

@Composable
private fun Fields(code: PixCheck.Code) {
    Field(stringResource(R.string.bipe_pix_check_field_name), code.name)
    Field(stringResource(R.string.bipe_pix_check_field_city), code.city)
    Field(stringResource(R.string.bipe_pix_check_field_amount), code.amount?.let(::formatAmount) ?: stringResource(R.string.bipe_pix_check_amount_open))
    if (code.dynamic) {
        // Plain text only: the charge location is never opened or fetched.
        Field(stringResource(R.string.bipe_pix_check_field_charge_host), code.urlHost)
    } else {
        Field(stringResource(R.string.bipe_pix_check_field_key), code.key)
    }
    Field(stringResource(R.string.bipe_pix_check_field_info), code.info)
    Field(stringResource(R.string.bipe_pix_check_field_txid), code.txid)
}

@Composable
private fun Field(title: String, value: String?) {
    if (value.isNullOrEmpty()) return
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        SelectionContainer { Text(value, color = colors.inkPrimary) }
    }
}

@Composable
private fun Caution(text: String) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.trustDangerWash, RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = colors.trustDangerInk)
    }
}

/** Other Pix keys in the text, each with every matching type joined by "or". */
@Composable
private fun KeysReport(keys: List<PixCheck.Key>) {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bipe_pix_check_keys_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        keys.forEach { key ->
            val labels = key.types.map { stringResource(typeLabel(it)) }
            var joined = labels.firstOrNull() ?: stringResource(R.string.bipe_pix_check_key_type_unknown)
            labels.drop(1).forEach { next -> joined = stringResource(R.string.bipe_pix_check_key_types_or, joined, next) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(joined, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                SelectionContainer { Text(key.value, color = colors.inkPrimary) }
            }
        }
    }
}

private fun typeLabel(type: PixCheck.KeyType): Int = when (type) {
    PixCheck.KeyType.CPF -> R.string.bipe_pix_check_key_type_cpf
    PixCheck.KeyType.CNPJ -> R.string.bipe_pix_check_key_type_cnpj
    PixCheck.KeyType.PHONE -> R.string.bipe_pix_check_key_type_phone
    PixCheck.KeyType.EMAIL -> R.string.bipe_pix_check_key_type_email
    PixCheck.KeyType.RANDOM -> R.string.bipe_pix_check_key_type_random
    PixCheck.KeyType.UNKNOWN -> R.string.bipe_pix_check_key_type_unknown
}

/** The rule warning: the scam-warning title and the mapped text. */
@Composable
private fun WarningCard(kind: ScamWarningKind) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.trustCautionWash, RoundedCornerShape(10.dp)).padding(8.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.bipe_scam_warning_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.trustCautionInk)
            Text(stringResource(kind.messageRes), style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
        }
    }
}

/** The decimal amount of the code in BRL, from its digits: no binary rounding. */
internal fun formatAmount(value: String): String {
    val decimal = value.toBigDecimalOrNull() ?: return value
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { currency = Currency.getInstance("BRL") }
    return format.format(decimal)
}
