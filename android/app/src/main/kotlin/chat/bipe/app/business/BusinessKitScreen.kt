// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.app.people.QrCode
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.R
import chat.bipe.shared.api.myHandle
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch

/** Spec business-kit: a button for the company's site, a QR code and ready lines, for its own channels (ADR 0082). */
@Composable
fun BusinessKitScreen(business: BusinessApi, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.bipe_app_copied)
    val copy: (String) -> Unit = { text ->
        copyText(context, text)
        notice = copied
    }

    LaunchedEffect(Unit) {
        link = runCatching { business.withOpenId { myHandle(it) } }.getOrNull()?.handle?.let { BipeProfileLink.url(it) }
        loaded = true
    }

    Column(Modifier.fillMaxSize().testTag(BipeScreen.BUSINESS_KIT.id)) {
        GroupTopBar(stringResource(R.string.bipe_business_kit_title), onBack = onClose)
        val address = link
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            address == null -> Text(
                stringResource(R.string.bipe_public_profile_needs_handle),
                Modifier.padding(16.dp),
                color = colors.inkSecondary,
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.bipe_business_kit_intro), Modifier.padding(16.dp), color = colors.inkPrimary)
                notice?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = colors.inkSecondary) }

                GroupSectionHeader(stringResource(R.string.bipe_business_kit_button_header))
                val code = stringResource(
                    R.string.bipe_business_kit_button_code,
                    address,
                    stringResource(R.string.bipe_business_kit_button_label),
                )
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.inkPrimary)
                    BipeOutlinedButton(onClick = { copy(code) }) { Text(stringResource(R.string.bipe_business_kit_copy_code)) }
                    Text(stringResource(R.string.bipe_business_kit_button_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }

                GroupSectionHeader(stringResource(R.string.bipe_business_kit_qr_header))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QrCode(address)
                    BipeOutlinedButton(onClick = { scope.launch { shareQr(context, address, "bipe-qr.png") } }) {
                        Text(stringResource(R.string.bipe_business_kit_share_png))
                    }
                    BipeOutlinedButton(onClick = { scope.launch { shareQrSvg(context, address, "bipe-qr.svg") } }) {
                        Text(stringResource(R.string.bipe_business_kit_share_svg))
                    }
                    Text(stringResource(R.string.bipe_business_kit_qr_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }

                GroupSectionHeader(stringResource(R.string.bipe_business_kit_lines_header))
                listOf(
                    stringResource(R.string.bipe_business_kit_line_site, address),
                    stringResource(R.string.bipe_business_kit_line_email, address),
                    stringResource(R.string.bipe_business_kit_line_receipt, address),
                ).forEach { line ->
                    Text(
                        line,
                        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.bipe_app_copy)) { copy(line) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        color = colors.inkPrimary,
                    )
                }
                Text(stringResource(R.string.bipe_business_kit_rule), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Bipe", text))
}
