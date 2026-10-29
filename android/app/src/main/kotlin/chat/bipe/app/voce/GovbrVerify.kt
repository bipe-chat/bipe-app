// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.settings.SettingsPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeGovbrFlow
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeGovbrReason
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The open gov.br verification of this app session (spec govbr-verify receiveFile). A signed PDF shared to Bipe goes to
 * the flow that waits for it; with no open verification it shows bipe_govbr_no_open_verification and is not uploaded.
 */
object GovbrInbox {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var flow: BipeGovbrFlow? = null

    fun open(flow: BipeGovbrFlow) {
        this.flow = flow
    }

    fun close(flow: BipeGovbrFlow) {
        if (this.flow === flow) this.flow = null
    }

    /** true for an ACTION_SEND or ACTION_VIEW intent that carries a PDF. */
    fun isPdf(intent: Intent?): Boolean =
        (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW) && intent.type == "application/pdf"

    /** Action receiveFile, from MainActivity. */
    fun receive(context: Context, intent: Intent) {
        val uri = (if (intent.action == Intent.ACTION_SEND) intent.streamUri() else intent.data) ?: return
        val open = flow?.takeIf { BipeRegistries.GOVBR_ENABLED && it.isWaiting }
        if (open == null) {
            Toast.makeText(context, R.string.bipe_govbr_no_open_verification, Toast.LENGTH_LONG).show()
            return
        }
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            open.submit(withContext(Dispatchers.IO) { readPdf(resolver, uri) })
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    /** The file bytes, or null when it cannot be read or is over the API's limit. */
    internal fun readPdf(resolver: android.content.ContentResolver, uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > BipeRegistries.GOVBR_MAX_DOCUMENT_BYTES) return@use null
            }
            output.toByteArray()
        }
    }.getOrNull()
}

/** Opens the gov.br app by package name, else its Play page (registries/govbr.json). */
private fun openGovbrApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(BipeRegistries.GOVBR_ANDROID_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(BipeRegistries.GOVBR_PLAY_STORE_URL))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No store and no gov.br app: bipe_govbr_get_app stays on screen.
    }
}

/** Spec govbr-verify (bipe-chat/bipe#544): trust step 5 in two taps. Opens only from the govbr step of trust-profile. */
@Composable
fun GovbrVerifyScreen(session: AccountSession, api: BipeApiClient, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(session) { BipeGovbrFlow(api) { session.accessToken() } }
    val phase by flow.phase.collectAsState()
    DisposableEffect(flow) {
        GovbrInbox.open(flow)
        onDispose { GovbrInbox.close(flow) }
    }
    // Action start, second half: the system save dialog, then gov.br opens.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val waiting = flow.phase.value as? BipeGovbrFlow.Phase.WaitingForSignature
        if (uri != null && waiting != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(waiting.pdf) } }
        }
        openGovbrApp(context)
    }
    // Action alreadySigned.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                flow.submit(withContext(Dispatchers.IO) { GovbrInbox.readPdf(context.contentResolver, uri) })
            }
        }
    }
    LaunchedEffect(phase) {
        if (phase is BipeGovbrFlow.Phase.WaitingForSignature) save.launch(BipeRegistries.GOVBR_FILE_NAME)
    }

    SettingsPage(stringResource(R.string.bipe_govbr_title), BipeScreen.GOVBR_VERIFY.id, onBack = onClose) {
        item(key = "govbr") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val current = phase) {
                    BipeGovbrFlow.Phase.Ready -> {
                        GovbrIntro()
                        BipeButton(onClick = { scope.launch { flow.start() } }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_govbr_start))
                        }
                    }
                    BipeGovbrFlow.Phase.Preparing, BipeGovbrFlow.Phase.Checking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp))
                        if (current == BipeGovbrFlow.Phase.Checking) {
                            Text(stringResource(R.string.bipe_govbr_checking), color = colors.inkSecondary)
                        }
                    }
                    is BipeGovbrFlow.Phase.WaitingForSignature -> {
                        GovbrIntro()
                        BipeButton(onClick = { pick.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_govbr_already_signed))
                        }
                        TextButton(onClick = { openGovbrApp(context) }) {
                            Text(stringResource(R.string.bipe_govbr_get_app), color = colors.accentInk)
                        }
                    }
                    is BipeGovbrFlow.Phase.Verified -> {
                        Text(stringResource(R.string.bipe_govbr_verified, current.name.orEmpty()), style = MaterialTheme.typography.titleMedium,
                            color = colors.inkPrimary)
                        BipeButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_close)) }
                    }
                    is BipeGovbrFlow.Phase.Rejected -> {
                        Text(stringResource(reasonText(current.reason)), color = colors.trustDangerInk)
                        BipeButton(onClick = { scope.launch { flow.start() } }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_govbr_retry))
                        }
                    }
                    BipeGovbrFlow.Phase.Failed -> BipeButton(onClick = { scope.launch { flow.start() } }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_govbr_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun GovbrIntro() {
    val colors = BipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bipe_govbr_intro), color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_govbr_needs_level), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        listOf(R.string.bipe_govbr_step_save, R.string.bipe_govbr_step_sign, R.string.bipe_govbr_step_share).forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((index + 1).toString(), color = colors.inkSecondary)
                Text(stringResource(line), color = colors.inkPrimary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.bipe_govbr_safety), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}

private fun reasonText(reason: BipeGovbrReason): Int = when (reason) {
    BipeGovbrReason.NOT_SIGNED -> R.string.bipe_govbr_reason_not_signed
    BipeGovbrReason.NOT_GOVBR -> R.string.bipe_govbr_reason_not_govbr
    BipeGovbrReason.WRONG_DOCUMENT -> R.string.bipe_govbr_reason_wrong_document
    BipeGovbrReason.EXPIRED -> R.string.bipe_govbr_reason_expired
    BipeGovbrReason.CPF_IN_USE -> R.string.bipe_govbr_reason_cpf_in_use
    BipeGovbrReason.CHANGED_AFTER_SIGNING -> R.string.bipe_govbr_reason_changed_after_signing
}
