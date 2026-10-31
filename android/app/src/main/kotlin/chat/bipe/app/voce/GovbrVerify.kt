// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.BuildConfig
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.settings.SettingsPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BipeGovbrFlow
import chat.bipe.shared.BipeGovbrReason
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spec govbr-verify, action receiveDocument: the open gov.br check of this app run. A signed PDF that reaches the app
 * (share or open with, alias `.GovbrDocument`) finds the check here. Only the step 5 row of trust-profile opens a check
 * (bipe-chat/bipe#277). One per process, in [BipeApplication.govbr].
 */
class GovbrInbox {
    private var flow: BipeGovbrFlow? = null
    private var flowUserId: String? = null

    /** The challenge whose PDF the screen already saved: a recomposition or a new activity does not save it again. */
    internal var savedChallenge: BipeGovbrFlow.Phase? = null

    /** The flow for the screen of [session]. A screen that opens again in the same app run keeps the open check. */
    fun begin(session: AccountSession, api: BipeApiClient): BipeGovbrFlow {
        flow?.takeIf { flowUserId == session.userId }?.let { return it }
        return BipeGovbrFlow(api) { session.accessToken() }.also {
            flow = it
            flowUserId = session.userId
            savedChallenge = null
        }
    }

    /** The screen closed: a PDF that comes later finds no open check. */
    fun end() {
        flow = null
        flowUserId = null
        savedChallenge = null
    }

    /** A PDF from [MainActivity]. With no open check: a message, and nothing is uploaded. */
    suspend fun receive(context: Context, pdf: ByteArray) {
        val open = flow
        if (open == null) {
            Toast.makeText(context, R.string.bipe_govbr_no_open_verification, Toast.LENGTH_LONG).show()
            return
        }
        open.receive(pdf)
    }

    companion object {
        private const val ALIAS = "chat.bipe.app.GovbrDocument"

        /** A share or open with of a PDF through the alias. Always false with the build flag off. */
        fun isDocumentIntent(intent: Intent): Boolean =
            BuildConfig.BIPE_GOVBR && intent.component?.className == ALIAS &&
                (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW)

        fun documentUri(intent: Intent): Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> intent.data
        }

        /**
         * Reads a received or picked file while the grant exists. A file over the API limit reads as empty (rule notSigned).
         * The app keeps no copy of the signed file.
         */
        suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
            val limit = BipeGovbrFlow.MAX_DOCUMENT_BYTES
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (output.size() <= limit) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }.getOrNull() ?: ByteArray(0)
            if (bytes.size > limit) ByteArray(0) else bytes
        }
    }
}

/** Spec govbr-verify: two taps in Bipe. Start saves the challenge PDF to Downloads and opens the gov.br app. */
@Composable
fun GovbrVerifyScreen(session: AccountSession, api: BipeApiClient, onVerified: (BadgeLevel?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val inbox = app.govbr
    val flow = remember(session) { inbox.begin(session, api) }
    val phase by flow.phase.collectAsState()
    val showsNoOpen by flow.showsNoOpenVerification.collectAsState()
    var needsApp by remember { mutableStateOf(false) }
    val colors = BipeTheme.colors
    val close = {
        inbox.end()
        onBack()
    }
    val start: () -> Unit = { app.scope.launch { flow.start() } }
    // Android 8 and 9 have no MediaStore Downloads: the system save dialog takes the PDF instead.
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val waiting = flow.phase.value as? BipeGovbrFlow.Phase.WaitingForSignature
        if (uri != null && waiting != null) app.scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(waiting.pdf) } != null }.getOrDefault(false)
            }
            if (written) needsApp = !openGovbrApp(context)
        }
    }
    // Action alreadySigned.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.scope.launch { flow.receive(GovbrInbox.read(context, uri)) }
    }
    LaunchedEffect(phase) {
        val current = phase
        if (current is BipeGovbrFlow.Phase.WaitingForSignature && inbox.savedChallenge !== current) {
            inbox.savedChallenge = current
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (saveToDownloads(context, current.pdf, current.fileName)) needsApp = !openGovbrApp(context)
            } else {
                createDocument.launch(current.fileName)
            }
        }
        if (current is BipeGovbrFlow.Phase.Verified) onVerified(flow.verifiedLevel)
    }

    SettingsPage(stringResource(R.string.bipe_govbr_title), BipeScreen.GOVBR_VERIFY.id, onBack = close) {
        item(key = "govbr-intro") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bipe_govbr_intro), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                StepLine(1, stringResource(R.string.bipe_govbr_step_save))
                StepLine(2, stringResource(R.string.bipe_govbr_step_sign))
                StepLine(3, stringResource(R.string.bipe_govbr_step_share))
                Text(stringResource(R.string.bipe_govbr_needs_level), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
        item(key = "govbr-phase") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val current = phase) {
                    BipeGovbrFlow.Phase.Ready, BipeGovbrFlow.Phase.Preparing -> {
                        val preparing = current == BipeGovbrFlow.Phase.Preparing
                        BipeButton(onClick = start, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                            if (preparing) CircularProgressIndicator(Modifier.size(18.dp), color = colors.inkOnAccent, strokeWidth = 2.dp)
                            else Text(stringResource(R.string.bipe_govbr_start))
                        }
                    }
                    is BipeGovbrFlow.Phase.WaitingForSignature -> {
                        if (needsApp) Text(stringResource(R.string.bipe_govbr_get_app), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                        BipeButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_govbr_already_signed))
                        }
                        BipeOutlinedButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_govbr_retry)) }
                    }
                    BipeGovbrFlow.Phase.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = colors.accentFill, strokeWidth = 2.dp)
                        Text(stringResource(R.string.bipe_govbr_checking), color = colors.inkPrimary, modifier = Modifier.padding(start = 12.dp))
                    }
                    is BipeGovbrFlow.Phase.Verified -> {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess, modifier = Modifier.size(22.dp))
                            Text(
                                current.name?.let { stringResource(R.string.bipe_govbr_verified, it) } ?: stringResource(R.string.bipe_govbr_verified_no_name),
                                color = colors.inkPrimary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        BipeButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_close)) }
                    }
                    is BipeGovbrFlow.Phase.Rejected -> {
                        Text(stringResource(reasonText(current.reason)), color = colors.inkPrimary)
                        BipeButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_govbr_retry)) }
                    }
                    BipeGovbrFlow.Phase.Failed -> {
                        Text(stringResource(R.string.bipe_error_generic), color = colors.inkPrimary)
                        BipeButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_govbr_retry)) }
                    }
                }
                if (showsNoOpen) {
                    Text(stringResource(R.string.bipe_govbr_no_open_verification), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
            }
        }
        item(key = "govbr-safety") {
            Text(stringResource(R.string.bipe_govbr_safety), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    val colors = BipeTheme.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(number.toString(), style = MaterialTheme.typography.titleSmall, color = colors.inkSecondary, modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.padding(start = 12.dp))
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

/** Saves the challenge PDF to Downloads, where the gov.br file picker shows the newest file first (Android 10 and later). */
@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveToDownloads(context: Context, pdf: ByteArray, fileName: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext false
    val written = runCatching { resolver.openOutputStream(uri)?.use { it.write(pdf) } != null }.getOrDefault(false)
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return@withContext false
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    runCatching { resolver.update(uri, values, null, null) }.isSuccess
}

/** Opens the gov.br app by its package name, else its Play page. Returns false when the app is not installed. */
private fun openGovbrApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(BipeRegistries.GOVBR_ANDROID_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(BipeRegistries.GOVBR_ANDROID_STORE_URL))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    return launch != null
}
